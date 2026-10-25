/**
 * In-world visualisation of what the system measures and decides (overlay layer: excluded from AO and the
 * camera feed):
 *  - the ToF cone (18°, 1.2 m) with ring marks at the proposed band edges 0.5 / 0.9 / 1.2 m;
 *  - the beam to the measured return (VALID: ink, NEAR distances in hazard yellow; UNKNOWN: dashed grey);
 *  - a dotted grey line to an object that exists but lies beyond 1.2 m (ground truth the sensor cannot report);
 *  - the ESP32-CAM frustum, and a floor footprint for the plan view;
 *  - an outline around the object the engine is warning about (yellow when its band is NEAR).
 */
import * as THREE from "three";
import { BAND_FAR_MM, BAND_MID_MM, BAND_NEAR_MM, RANGE_UNKNOWN, RANGE_VALID, type Band } from "../contract";
import type { Collider, ToFResult } from "../sensor/tof";
import { HALF_ANGLE_RAD } from "../sensor/tof";
import { HAZARD, INK, LAYER_OVERLAY, setLayer } from "./stage";

const R_AT = (d: number): number => d * Math.tan(HALF_ANGLE_RAD);
const GREY = new THREE.Color("#8d8f96");

function circle(radius: number, z: number, segs = 48): THREE.Vector3[] {
  const pts: THREE.Vector3[] = [];
  for (let i = 0; i <= segs; i++) {
    const a = (i / segs) * Math.PI * 2;
    pts.push(new THREE.Vector3(Math.cos(a) * radius, Math.sin(a) * radius, z));
  }
  return pts;
}

export class SensorOverlay {
  readonly group = new THREE.Group();        // follows the brow (position + yaw)
  private floorGroup = new THREE.Group();    // follows the wearer's feet
  private coneMat: THREE.MeshBasicMaterial;
  private lineMat: THREE.LineBasicMaterial;
  private nearRingMat: THREE.LineBasicMaterial;
  private beam: THREE.Line;
  private beamMat = new THREE.LineBasicMaterial({ color: INK, transparent: true, opacity: 0.9, depthTest: false });
  private dashed: THREE.Line;
  private dashedMat = new THREE.LineDashedMaterial({ color: GREY, dashSize: 0.03, gapSize: 0.025, transparent: true, opacity: 0.85, depthTest: false });
  private truth: THREE.Line;
  private truthMat = new THREE.LineDashedMaterial({ color: GREY, dashSize: 0.012, gapSize: 0.03, transparent: true, opacity: 0.7 });
  private marker: THREE.Mesh;
  private markerMat = new THREE.MeshBasicMaterial({ color: INK, side: THREE.DoubleSide, depthTest: false, transparent: true });
  private hatch: THREE.CanvasTexture;
  private outline: THREE.LineSegments;
  private outlineMat = new THREE.LineBasicMaterial({ color: INK, transparent: true, opacity: 0.9, depthTest: false });
  private dim = 1;

  constructor(scene: THREE.Scene) {
    const len = BAND_FAR_MM / 1000;
    // cone surface
    const coneGeo = new THREE.ConeGeometry(R_AT(len), len, 48, 1, true);
    coneGeo.translate(0, -len / 2, 0);
    coneGeo.rotateX(Math.PI / 2);                     // apex at the origin, opening along −Z
    this.coneMat = new THREE.MeshBasicMaterial({ color: INK, transparent: true, opacity: 0.045, depthWrite: false, side: THREE.DoubleSide });
    this.group.add(new THREE.Mesh(coneGeo, this.coneMat));
    // silhouette + band rings
    this.lineMat = new THREE.LineBasicMaterial({ color: INK, transparent: true, opacity: 0.32 });
    const edges: THREE.Vector3[] = [];
    for (let k = 0; k < 4; k++) {
      const a = (k / 4) * Math.PI * 2 + Math.PI / 4;
      edges.push(new THREE.Vector3(0, 0, 0), new THREE.Vector3(Math.cos(a) * R_AT(len), Math.sin(a) * R_AT(len), -len));
    }
    this.group.add(new THREE.LineSegments(new THREE.BufferGeometry().setFromPoints(edges), this.lineMat));
    for (const mm of [BAND_MID_MM, BAND_FAR_MM]) {
      this.group.add(new THREE.Line(new THREE.BufferGeometry().setFromPoints(circle(R_AT(mm / 1000), -mm / 1000)), this.lineMat));
    }
    this.nearRingMat = new THREE.LineBasicMaterial({ color: HAZARD.clone().multiplyScalar(0.82), transparent: true, opacity: 0.95 });
    this.group.add(new THREE.Line(new THREE.BufferGeometry().setFromPoints(circle(R_AT(BAND_NEAR_MM / 1000), -BAND_NEAR_MM / 1000)), this.nearRingMat));
    // camera frustum (ESP32-CAM, ~66° × 50°), short so it reads as a mount, not a beam
    const cam: THREE.Vector3[] = [];
    const tx = Math.tan((66 / 2) * Math.PI / 180) * 0.32, ty = Math.tan((50 / 2) * Math.PI / 180) * 0.32;
    const corners = [[-tx, ty], [tx, ty], [tx, -ty], [-tx, -ty]];
    corners.forEach(([x, y], i) => {
      const [nx, ny] = corners[(i + 1) % 4];
      cam.push(new THREE.Vector3(0.018, 0, 0), new THREE.Vector3(x, y, -0.32));
      cam.push(new THREE.Vector3(x, y, -0.32), new THREE.Vector3(nx, ny, -0.32));
    });
    this.group.add(new THREE.LineSegments(new THREE.BufferGeometry().setFromPoints(cam),
      new THREE.LineBasicMaterial({ color: INK, transparent: true, opacity: 0.14 })));
    // measured beam, unknown beam, ground truth beyond range
    this.beam = new THREE.Line(new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(), new THREE.Vector3()]), this.beamMat);
    this.dashed = new THREE.Line(new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(), new THREE.Vector3()]), this.dashedMat);
    this.truth = new THREE.Line(new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(), new THREE.Vector3()]), this.truthMat);
    for (const l of [this.beam, this.dashed, this.truth]) { l.frustumCulled = false; l.renderOrder = 10; scene.add(l); }
    // return marker
    this.hatch = hatchTexture();
    this.marker = new THREE.Mesh(new THREE.PlaneGeometry(0.07, 0.07), this.markerMat);
    this.marker.renderOrder = 11;
    scene.add(this.marker);
    // floor footprint (plan view)
    const fp = new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(0, 0, -0.13), new THREE.Vector3(-R_AT(len), 0, -0.13 - len), new THREE.Vector3(R_AT(len), 0, -0.13 - len),
    ]);
    this.floorGroup.add(new THREE.Mesh(fp, new THREE.MeshBasicMaterial({ color: INK, transparent: true, opacity: 0.055, depthWrite: false, side: THREE.DoubleSide })));
    this.floorGroup.add(new THREE.LineLoop(fp, this.lineMat));
    // engine-target outline
    this.outline = new THREE.LineSegments(new THREE.EdgesGeometry(new THREE.BoxGeometry(1, 1, 1)), this.outlineMat);
    this.outline.renderOrder = 12;
    scene.add(this.outline);
    scene.add(this.group, this.floorGroup);
    for (const o of [this.group, this.floorGroup, this.beam, this.dashed, this.truth, this.marker, this.outline]) setLayer(o, LAYER_OVERLAY);
  }

  update(brow: THREE.Vector3, yaw: number, feet: THREE.Vector3, tof: ToFResult | null, colliders: readonly Collider[],
    alertBand: Band | null): void {
    this.group.position.copy(brow);
    this.group.rotation.set(0, yaw, 0);
    this.floorGroup.position.set(feet.x, feet.y + 0.018, feet.z);
    this.floorGroup.rotation.set(0, yaw, 0);
    this.beam.visible = this.dashed.visible = this.truth.visible = this.marker.visible = false;
    this.outline.visible = false;
    if (!tof || !tof.hit) return;
    const hit = new THREE.Vector3(...tof.hit);
    const forward = new THREE.Vector3(-Math.sin(yaw), 0, -Math.cos(yaw));
    if (tof.status === RANGE_VALID) {
      setLine(this.beam, brow, hit);
      const near = tof.mm < BAND_NEAR_MM;
      this.beamMat.color.copy(near ? HAZARD.clone().multiplyScalar(0.85) : INK);
      this.placeMarker(hit, forward, near ? HAZARD : INK, null);
    } else if (tof.status === RANGE_UNKNOWN) {
      setLine(this.dashed, brow, hit);
      this.dashed.computeLineDistances();
      this.placeMarker(hit, forward, GREY, this.hatch);
    } else if (tof.truthM !== null) {
      // something is there, beyond what the sensor can report: draw from the 1.2 m ring outwards
      const start = brow.clone().addScaledVector(new THREE.Vector3().subVectors(hit, brow).normalize(), BAND_FAR_MM / 1000);
      setLine(this.truth, start, hit);
      this.truth.computeLineDistances();
    }
    if (alertBand && tof.collider >= 0 && tof.status === RANGE_VALID) {
      const c = colliders[tof.collider];
      this.outline.position.set(c.cx, c.cy, c.cz);
      this.outline.rotation.set(0, c.yaw, 0);
      this.outline.scale.set(c.hx * 2 + 0.03, c.hy * 2 + 0.03, c.hz * 2 + 0.03);
      this.outlineMat.color.copy(alertBand === "NEAR" ? HAZARD.clone().multiplyScalar(0.85) : INK);
      this.outline.visible = true;
    }
  }

  /** Engine not READY: keep drawing the physics, but visibly de-emphasised. */
  setTrusted(trusted: boolean): void {
    this.dim = trusted ? 1 : 0.4;
    this.coneMat.opacity = 0.045 * this.dim;
    this.lineMat.opacity = 0.32 * this.dim;
    this.nearRingMat.opacity = 0.95 * this.dim;
  }

  private placeMarker(at: THREE.Vector3, facing: THREE.Vector3, color: THREE.Color, map: THREE.Texture | null): void {
    this.marker.position.copy(at).addScaledVector(facing, -0.004);
    this.marker.lookAt(at.clone().sub(facing));
    this.markerMat.color.copy(color);
    if (this.markerMat.map !== map) { this.markerMat.map = map; this.markerMat.needsUpdate = true; }
    this.marker.visible = true;
  }
}

function setLine(line: THREE.Line, a: THREE.Vector3, b: THREE.Vector3): void {
  const p = line.geometry.getAttribute("position") as THREE.BufferAttribute;
  p.setXYZ(0, a.x, a.y, a.z);
  p.setXYZ(1, b.x, b.y, b.z);
  p.needsUpdate = true;
  line.geometry.computeBoundingSphere();
  line.visible = true;
}

function hatchTexture(): THREE.CanvasTexture {
  const c = document.createElement("canvas");
  c.width = c.height = 32;
  const g = c.getContext("2d")!;
  g.fillStyle = "#ffffff";
  g.fillRect(0, 0, 32, 32);
  g.strokeStyle = "#5c5e66";
  g.lineWidth = 3;
  for (let k = -32; k < 64; k += 8) { g.beginPath(); g.moveTo(k, 0); g.lineTo(k + 32, 32); g.stroke(); }
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

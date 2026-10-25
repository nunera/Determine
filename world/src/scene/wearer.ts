/**
 * The wearer: maquette figure + the v2 compact headband (forward ToF and ESP32-CAM at the brow, XIAO + Vibro on
 * the rear strap), a Bluetooth bone-conduction headset at the temples (the phone's audio route), and a white cane
 * whose tip sweeps the floor. The cane arc on the floor and the ToF cone at the brow tell the core story: the cane
 * covers the ground, nothing covers the head — until the sensor does, within 1.2 m.
 */
import * as THREE from "three";
import { createFigure, BROW_Y, type Figure } from "./figure";
import { LAYER_OVERLAY, LAYER_WEARER, LEFT_BLUE, MAT, RIGHT_RED, setLayer } from "./stage";

const CANE_REACH = 0.95;       // horizontal reach of the tip ahead of the body
const SWEEP = 0.42;            // lateral half-sweep of the tip
const STRIDE = 0.68;

interface Pulse { mesh: THREE.Mesh; mat: THREE.MeshBasicMaterial; t0: number; dur: number; scale: number }

export class Wearer {
  readonly root = new THREE.Group();
  readonly figure: Figure;
  /** Brow origin of the sensor and camera, in the head frame. */
  readonly brow = new THREE.Object3D();
  private cane: THREE.Mesh;
  private caneArc: THREE.Mesh;
  private pulses: Pulse[] = [];
  private pulseGeo = new THREE.RingGeometry(0.075, 0.085, 40, 1, -Math.PI / 2.4, Math.PI / 1.2);
  private hapticGeo = new THREE.RingGeometry(0.06, 0.068, 40);
  private padL: THREE.Object3D;
  private padR: THREE.Object3D;
  private deviceMats: THREE.MeshStandardMaterial[] = [];
  private phase = 0;
  private tipPos = new THREE.Vector3();

  constructor(scene: THREE.Scene) {
    this.figure = createFigure(MAT.clay);
    this.root.add(this.figure.root);
    const head = this.figure.head;

    const deviceInk = new THREE.MeshStandardMaterial({ color: "#26262b", roughness: 0.55, envMapIntensity: 0.5 });
    const lens = new THREE.MeshStandardMaterial({ color: "#0d0d10", roughness: 0.15, metalness: 0.2 });
    this.deviceMats.push(deviceInk);
    // band
    const band = new THREE.Mesh(new THREE.TorusGeometry(0.104, 0.011, 10, 56), deviceInk);
    band.rotation.x = Math.PI / 2;
    band.scale.set(0.95, 1.06, 1);
    band.position.y = 0.035;
    head.add(band);
    // brow module: ToF window + camera lens
    const brow = new THREE.Mesh(new THREE.BoxGeometry(0.075, 0.032, 0.026), deviceInk);
    brow.position.set(0, 0, -0.112);
    head.add(brow);
    const tof = new THREE.Mesh(new THREE.BoxGeometry(0.014, 0.01, 0.004), lens);
    tof.position.set(-0.02, 0, -0.126);
    head.add(tof);
    const cam = new THREE.Mesh(new THREE.CylinderGeometry(0.007, 0.007, 0.006, 16), lens);
    cam.rotation.x = Math.PI / 2;
    cam.position.set(0.018, 0, -0.127);
    head.add(cam);
    this.brow.position.set(0, 0, -0.13);
    head.add(this.brow);
    // rear strap: XIAO ESP32-C3 + Modulino Vibro
    const rear = new THREE.Mesh(new THREE.BoxGeometry(0.055, 0.04, 0.026), deviceInk);
    rear.position.set(0, 0.02, 0.112);
    head.add(rear);
    // Bluetooth bone-conduction headset pads in front of the ears (the phone's audio route)
    const pad = (side: number, color: THREE.Color): THREE.Object3D => {
      const g = new THREE.Group();
      const p = new THREE.Mesh(new THREE.CylinderGeometry(0.016, 0.016, 0.01, 20), deviceInk);
      p.rotation.z = Math.PI / 2;
      g.add(p);
      const dot = new THREE.Mesh(new THREE.CircleGeometry(0.005, 16), new THREE.MeshBasicMaterial({ color }));
      dot.position.x = side * 0.0055;
      dot.rotation.y = side * Math.PI / 2;
      g.add(dot);
      g.position.set(side * 0.1, -0.03, -0.02);
      head.add(g);
      return g;
    };
    this.padL = pad(-1, LEFT_BLUE);
    this.padR = pad(1, RIGHT_RED);

    // cane: white shaft with an ink grip and tip
    const caneGeo = new THREE.CylinderGeometry(0.0085, 0.0085, 1, 10);
    caneGeo.translate(0, 0.5, 0);
    this.cane = new THREE.Mesh(caneGeo, MAT.white);
    this.cane.castShadow = true;
    this.root.add(this.cane);
    // cane coverage arc on the floor (overlay)
    const arcGeo = new THREE.RingGeometry(CANE_REACH - 0.012, CANE_REACH + 0.012, 48, 1,
      Math.PI / 2 - Math.atan(SWEEP / CANE_REACH), 2 * Math.atan(SWEEP / CANE_REACH));
    this.caneArc = new THREE.Mesh(arcGeo, new THREE.MeshBasicMaterial({
      color: "#131316", transparent: true, opacity: 0.38, depthWrite: false, side: THREE.DoubleSide,
    }));
    this.caneArc.rotation.x = -Math.PI / 2;
    this.caneArc.position.set(0, 0.014, 0);
    setLayer(this.caneArc, LAYER_OVERLAY);
    this.root.add(this.caneArc);

    this.root.traverse((o) => {
      if ((o as THREE.Mesh).isMesh && o !== this.caneArc) { o.castShadow = true; o.receiveShadow = true; }
    });
    for (const o of [this.figure.root, this.cane]) setLayer(o, LAYER_WEARER);
    scene.add(this.root);
  }

  /** Place and animate. distance: metres walked (drives the gait), speed: m/s, maxSpeed: course cruise speed. */
  update(x: number, floorY: number, z: number, yaw: number, distance: number, speed: number, maxSpeed: number,
    now: number): void {
    this.root.position.set(x, floorY, z);
    this.root.rotation.y = yaw;
    this.phase = (distance / STRIDE) * Math.PI;
    const amp = Math.min(1, speed / Math.max(0.3, maxSpeed));
    this.figure.pose(this.phase, amp, true);
    this.root.updateMatrixWorld(true);

    // cane from the hand to the tip; the tip sweeps with the stride (and idles with a small arc when standing)
    const hand = this.figure.rightHand.getWorldPosition(new THREE.Vector3());
    const sweep = amp > 0.05 ? Math.sin(this.phase) * SWEEP : Math.sin(now * 1.6) * SWEEP * 0.35;
    const local = new THREE.Vector3(sweep, 0.012, -CANE_REACH);
    this.tipPos.copy(local).applyMatrix4(this.root.matrixWorld);
    const dir = new THREE.Vector3().subVectors(this.tipPos, hand);
    const len = dir.length();
    this.cane.position.copy(this.root.worldToLocal(hand.clone()));
    const localDir = dir.clone().applyQuaternion(this.root.quaternion.clone().invert()).normalize();
    this.cane.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), localDir);
    this.cane.scale.set(1, len, 1);
    this.caneArc.position.set(0, 0.014, 0);

    // pulses
    this.pulses = this.pulses.filter((p) => {
      const k = (now - p.t0) / p.dur;
      if (k >= 1) { p.mesh.removeFromParent(); return false; }
      const e = 1 - (1 - k) * (1 - k);
      p.mesh.scale.setScalar(1 + e * p.scale);
      p.mat.opacity = (1 - k) * 0.95;
      return true;
    });
  }

  /** Phone audio on the headset: an arc at each temple, sized by that ear's linear gain. */
  audioPulse(gainL: number, gainR: number, now: number): void {
    if (gainL > 0.001) this.spawn(this.padL, LEFT_BLUE, gainL, now, -1);
    if (gainR > 0.001) this.spawn(this.padR, RIGHT_RED, gainR, now, 1);
  }

  /** Cue-board vibration: concentric ink rings at the rear strap (one motor, no side). */
  hapticPulse(intensity01: number, now: number): void {
    const mat = new THREE.MeshBasicMaterial({ color: "#131316", transparent: true, depthWrite: false, side: THREE.DoubleSide });
    const mesh = new THREE.Mesh(this.hapticGeo, mat);
    mesh.position.set(0, 0.02, 0.13);
    mesh.layers.set(LAYER_OVERLAY);
    this.figure.head.add(mesh);
    this.pulses.push({ mesh, mat, t0: now, dur: 0.45, scale: 0.6 + 1.6 * intensity01 });
  }

  private spawn(anchor: THREE.Object3D, color: THREE.Color, gain: number, now: number, side: number): void {
    const mat = new THREE.MeshBasicMaterial({ color, transparent: true, depthWrite: false, side: THREE.DoubleSide });
    const mesh = new THREE.Mesh(this.pulseGeo, mat);
    mesh.rotation.y = side * Math.PI / 2;
    mesh.position.x = side * 0.01;
    mesh.layers.set(LAYER_OVERLAY);
    anchor.add(mesh);
    this.pulses.push({ mesh, mat, t0: now, dur: 0.5, scale: 0.8 + 2.4 * Math.min(1, gain * 3) });
  }

  /** Dim the device when the engine is not READY (nothing fake: the hardware is still there, just not trusted). */
  setDeviceTrusted(trusted: boolean): void {
    for (const m of this.deviceMats) m.color.set(trusted ? "#26262b" : "#8d8d94");
  }

  browWorld(target = new THREE.Vector3()): THREE.Vector3 { return this.brow.getWorldPosition(target); }

  static readonly BROW_Y = BROW_Y;
}

/**
 * Smooth, stylised human figure (intentionally faceless), sized for a 1.74 m adult whose brow is at the sensor
 * height (1.6 m). Used for the wearer and pedestrians; materials distinguish their roles.
 */
import * as THREE from "three";

export interface Figure {
  root: THREE.Group;          // feet at origin, facing −Z
  head: THREE.Group;          // centre at brow height
  rightHand: THREE.Object3D;  // world position of the grip (for the cane)
  /** phase: radians of the gait cycle; amp: 0 standing … 1 full stride. caneArm: keep the right arm forward. */
  pose(phase: number, amp: number, caneArm: boolean): void;
}

const HIP_Y = 0.9;
const SHOULDER_Y = 1.42;
export const BROW_Y = 1.6;

export function createFigure(mat: THREE.Material): Figure {
  const root = new THREE.Group();
  const body = new THREE.Group();
  root.add(body);

  const limb = (len: number, r: number): THREE.Mesh => {
    const g = new THREE.CapsuleGeometry(r, len - 2 * r, 8, 24);
    g.translate(0, -len / 2, 0);              // pivot at the top joint
    return new THREE.Mesh(g, mat);
  };

  // legs
  const legs: THREE.Group[] = [];
  for (const side of [-1, 1]) {
    const hip = new THREE.Group();
    hip.position.set(side * 0.095, HIP_Y, 0);
    hip.add(limb(HIP_Y, 0.065));
    body.add(hip);
    legs.push(hip);
  }
  // pelvis + torso
  const pelvis = new THREE.Mesh(new THREE.CapsuleGeometry(0.13, 0.08, 8, 24), mat);
  pelvis.rotation.z = Math.PI / 2;
  pelvis.scale.set(1, 1, 0.75);
  pelvis.position.y = HIP_Y + 0.02;
  body.add(pelvis);
  const torso = new THREE.Mesh(new THREE.CapsuleGeometry(0.15, 0.36, 8, 24), mat);
  torso.scale.set(1.05, 1, 0.66);
  torso.position.y = 1.18;
  body.add(torso);
  const neck = new THREE.Mesh(new THREE.CylinderGeometry(0.045, 0.05, 0.1, 24), mat);
  neck.position.y = 1.5;
  body.add(neck);
  // head (sphere, no face)
  const head = new THREE.Group();
  head.position.y = BROW_Y;
  const skull = new THREE.Mesh(new THREE.SphereGeometry(0.105, 32, 24), mat);
  skull.scale.set(0.92, 1.08, 1);
  skull.position.y = 0.01;
  head.add(skull);
  body.add(head);
  // arms
  const arms: THREE.Group[] = [];
  for (const side of [-1, 1]) {
    const sh = new THREE.Group();
    sh.position.set(side * 0.2, SHOULDER_Y, 0);
    sh.add(limb(0.62, 0.045));
    body.add(sh);
    arms.push(sh);
  }
  const rightHand = new THREE.Object3D();
  rightHand.position.y = -0.6;
  arms[1].add(rightHand);

  return {
    root, head, rightHand,
    pose(phase, amp, caneArm) {
      const s = Math.sin(phase);
      legs[0].rotation.x = 0.34 * amp * s;
      legs[1].rotation.x = -0.34 * amp * s;
      arms[0].rotation.x = -0.28 * amp * s;
      arms[0].rotation.z = -0.05;
      if (caneArm) {
        arms[1].rotation.x = -0.62;            // forearm forward, cane hand near the midline
        arms[1].rotation.z = 0.2;
      } else {
        arms[1].rotation.x = 0.28 * amp * s;
        arms[1].rotation.z = 0.05;
      }
      body.position.y = 0.018 * amp * Math.abs(Math.cos(phase));
      body.rotation.y = 0.04 * amp * s;
    },
  };
}

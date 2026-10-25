import "@fontsource/atkinson-hyperlegible-next/400.css";
import "@fontsource/atkinson-hyperlegible-next/700.css";
import "@fontsource/atkinson-hyperlegible-next/800.css";
import "@fontsource/atkinson-hyperlegible-mono/500.css";
import "@fontsource/atkinson-hyperlegible-mono/600.css";

import { Bus } from "./contract";
import { createWorld } from "./scene/world";
import { createHud } from "./hud/hud";
import { createAudio } from "./audio/audio";
import { createLink } from "./link/link";
import { createRecorder } from "./data/recorder";
import { createDeviceProfile } from "./data/deviceProfile";
import { createHitlPanel } from "./hitl/hitlPanel";
import { mountScreenView, startScreenLeader } from "./hitl/screenView";
import { createStudio, studioParent } from "./studio";

const bus = new Bus();
const canvas = document.getElementById("world") as HTMLCanvasElement;
const hudRoot = document.getElementById("hud") as HTMLElement;
const studio = new URLSearchParams(location.search).get("studio") === "1";
if (studio) {
  const parentOrigin = studioParent();
  if (window.parent === window || !parentOrigin) {
    hudRoot.textContent = "Studio embedding refused: parentOrigin must be this origin or a loopback website on port 5175/4175.";
    canvas.hidden = true;
  } else {
    document.body.style.margin = "0";
    const detach = createStudio(bus, parentOrigin);
    const world = createWorld(canvas, bus);
    createAudio(bus);
    createLink(bus, world);
    window.addEventListener("pagehide", () => { detach(); world.dispose(); }, { once: true });
  }
} else if (new URLSearchParams(location.search).get("view") === "camera") {
  // Screen-in-the-loop: head camera only, follows the main page; no HUD, audio or bridge link.
  mountScreenView(bus, hudRoot);
  createWorld(canvas, bus, { view: "camera" });
} else {
  const world = createWorld(canvas, bus);
  createHud(hudRoot, bus);
  createRecorder(bus, hudRoot, world);
  createDeviceProfile(bus, hudRoot, world);
  createAudio(bus);
  createHitlPanel(bus, hudRoot);
  startScreenLeader(bus);
  createLink(bus, world);
}

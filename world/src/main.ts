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

const bus = new Bus();
const world = createWorld(document.getElementById("world") as HTMLCanvasElement, bus);
const hudRoot = document.getElementById("hud") as HTMLElement;
createHud(hudRoot, bus);
createRecorder(bus, hudRoot, world);
createDeviceProfile(bus, hudRoot, world);
createAudio(bus);
createLink(bus, world);

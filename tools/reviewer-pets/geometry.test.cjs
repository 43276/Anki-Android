// SPDX-License-Identifier: GPL-3.0-or-later
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = fs.readFileSync(path.resolve(__dirname,
    "../../AnkiDroid/src/main/assets/scripts/reviewer-pet-geometry.js"), "utf8");
const sandbox = vm.createContext({ Math: Object.assign(Object.create(Math), { random: () => 0 }) });
vm.runInContext(source, sandbox);
const geometry = sandbox.reviewerPetGeometry;
const bounds = { x: 10, y: 20, width: 400, height: 400 };
const box = (x, y, width, height) => ({ x, y, width, height });

test("normal placement fits the visible viewport and avoids content and controls", () => {
    const content = [box(10, 20, 400, 190)];
    const controls = [box(250, 250, 100, 70)];
    const placed = geometry.place(bounds, content, controls, 130, 60, 8);
    assert.ok(placed);
    assert.equal(placed.fallback, false);
    assert.equal(placed.width, 130);
    assert.ok(geometry.inside(placed, bounds));
    assert.ok(geometry.safe(placed, bounds, content.concat(controls), 8));
});

test("a safe existing position stays put, while single-click alternate placement moves", () => {
    const previous = box(18, 28, 130, 130);
    const kept = geometry.place(bounds, [], [], 130, 60, 8, previous);
    assert.equal(kept, previous);
    const moved = geometry.place(bounds, [], [], 130, 60, 8, previous, true);
    assert.ok(geometry.inside(moved, bounds));
    assert.ok(Math.hypot(moved.x - previous.x, moved.y - previous.y) >= 65);
});

test("automatic avoidance chooses a nearby vacancy while manual movement keeps its random destination", () => {
    const viewport = box(0, 0, 400, 400);
    const previous = box(300, 300, 100, 100);
    const controls = [box(390, 390, 10, 10)];
    const nearby = geometry.place(viewport, [], controls, 100, 60, 8, previous);
    assert.ok(geometry.safe(nearby, viewport, controls, 8));
    assert.ok(Math.hypot(nearby.x - previous.x, nearby.y - previous.y) < 45,
        "a vacancy only 43px away should avoid sending the pet across the viewport");
    const alternate = geometry.place(viewport, [], controls, 100, 60, 8, previous, true);
    assert.equal(alternate.x, 8);
    assert.equal(alternate.y, 8);
});

test("no normal vacancy uses only the smaller bottom-right fallback", () => {
    const placed = geometry.place(bounds, [bounds], [], 130, 60, 8);
    assert.ok(placed);
    assert.equal(placed.fallback, true);
    assert.equal(placed.width, 78);
    assert.equal(placed.height, 78);
    assert.equal(placed.x, 324);
    assert.equal(placed.y, 334);
    assert.ok(geometry.inside(placed, bounds));
});

test("fallback cannot cover an interactive control or its avoidance gap", () => {
    const controls = [box(390, 390, 20, 20)];
    assert.equal(geometry.place(bounds, [bounds], controls, 130, 60, 8), null);
    const justOutsideCorner = [box(315, 334, 4, 20)];
    assert.equal(geometry.place(bounds, [bounds], justOutsideCorner, 130, 60, 8), null);
});

test("a viewport too narrow for the fallback stays hidden", () => {
    const narrow = box(30, 40, 60, 200);
    assert.equal(geometry.place(narrow, [], [], 130, 60, 8), null);
    assert.equal(geometry.dock(box(0, 0, 20, 200), [], 100, 80, 8, 0, "left"), null);
    assert.equal(geometry.dock(box(0, 0, 400, 50), [], 100, 70, 8, 0, "left"), null);
});

test("edge docking protects the expanded hit area, beyond the exposed image", () => {
    const viewport = box(0, 0, 300, 200);
    // Only 20px of the image is exposed, but the click target extends to 24px.
    const controls = [box(21, 0, 1, 200)];
    const docked = geometry.dock(viewport, controls, 100, 80, 0, 70, "left");
    assert.ok(docked);
    assert.equal(docked.edge, "right");
    assert.equal(docked.hit.width, 24);
    assert.ok(geometry.safe(docked.hit, viewport, controls, 0));
    assert.equal(docked.x, 280);
});

test("docking uses the nearest safe vertical position on the preferred edge", () => {
    const viewport = box(0, 0, 300, 300);
    const controls = [box(0, 70, 30, 50)];
    const docked = geometry.dock(viewport, controls, 100, 70, 8, 80, "left");
    assert.ok(docked);
    assert.equal(docked.edge, "left");
    assert.equal(docked.y, 128);
    assert.ok(Math.abs(docked.x + 70) < 0.00001);
    assert.ok(geometry.safe(docked.hit, viewport, controls, 8));
});

test("docking returns null when controls occupy both usable borders", () => {
    const viewport = box(0, 0, 300, 300);
    const controls = [box(0, 0, 40, 300), box(260, 0, 40, 300)];
    assert.equal(geometry.dock(viewport, controls, 100, 70, 8, 100, "left"), null);
});

test("a tiny corner fallback expands its hit target and protects the entire target from controls", () => {
    const viewport = box(0, 0, 300, 300);
    const fallback = geometry.place(viewport, [viewport], [], 32, 25, 8);
    assert.ok(fallback);
    assert.equal(fallback.width, 8);
    assert.equal(fallback.height, 8);
    assert.equal(fallback.hit.x, 268);
    assert.equal(fallback.hit.y, 268);
    assert.equal(fallback.hit.width, 24);
    assert.equal(fallback.hit.height, 24);
    const controls = [box(269, 269, 4, 4)];
    assert.ok(geometry.safe(fallback, viewport, controls, 8), "control does not touch the small visible sprite");
    assert.equal(geometry.place(viewport, [viewport], controls, 32, 25, 8), null,
        "the extended touch target still must not cover the control");
    const docked = geometry.dock(viewport, [], 8, 70, 8, 284, "right");
    assert.ok(docked);
    assert.equal(docked.height, 8);
    assert.equal(docked.hit.width, 24);
    assert.equal(docked.hit.height, 24);
    assert.equal(docked.hit.y, 276);
    assert.ok(geometry.inside(docked.hit, viewport));
    assert.equal(geometry.dock(box(0, 0, 300, 20), [], 8, 70, 8, 0, "right"), null);
});

test("tiny docking moves vertically when only the extended hit area violates a control's gap", () => {
    const viewport = box(0, 0, 300, 300);
    const controls = [box(298, 86, 1, 2)];
    const original = geometry.dock(viewport, [], 8, 70, 8, 100, "right");
    assert.ok(original);
    assert.equal(geometry.intersects(original, controls[0], 8), false,
        "the 8px sprite itself clears the control's gap");
    assert.equal(geometry.safe(original.hit, viewport, controls, 8), false,
        "the extra touch area above the sprite must also clear that gap");
    const docked = geometry.dock(viewport, controls, 8, 70, 8, 100, "right");
    assert.ok(docked);
    assert.equal(docked.edge, "right");
    assert.equal(docked.y, 104);
    assert.equal(docked.width, 8);
    assert.equal(docked.hit.width, 24);
    assert.equal(docked.hit.height, 24);
    assert.ok(geometry.safe(docked.hit, viewport, controls, 8));
});

test("tiny docking stays hidden when both borders fit the sprite but cannot fit its touch area", () => {
    const viewport = box(0, 0, 300, 300);
    // Each border has an 8px vertical vacancy, smaller than the required 24px target.
    const controls = [box(0, 0, 2, 150), box(0, 158, 2, 142),
        box(298, 0, 2, 150), box(298, 158, 2, 142)];
    for (const edge of ["left", "right"]) {
        const original = geometry.dock(viewport, [], 8, 70, 0, 150, edge);
        assert.ok(original);
        assert.equal(controls.some(control => geometry.intersects(original, control)), false,
            "the exposed 8px sprite fits the border's vacancy");
        assert.equal(geometry.safe(original.hit, viewport, controls, 0), false);
        assert.equal(geometry.dock(viewport, controls, 8, 70, 0, 150, edge), null);
    }
});

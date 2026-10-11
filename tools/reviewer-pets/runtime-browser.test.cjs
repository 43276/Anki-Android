// SPDX-License-Identifier: GPL-3.0-or-later
"use strict";

// Uses the existing adjacent template project's Playwright installation.
// Run: node --test tools/reviewer-pets/runtime-browser.test.cjs
// Set ANKI_BROWSER_PATH when Chrome/Edge is installed in a nonstandard location.
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { createRequire } = require("node:module");
const requireTemplate = createRequire(path.resolve(__dirname, "../../../anki_template/package.json"));
const { chromium } = requireTemplate("playwright-core");
const assets = path.resolve(__dirname, "../../AnkiDroid/src/main/assets/scripts");
const image = "data:image/svg+xml," + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><circle cx="50" cy="50" r="40" fill="blue"/></svg>');
const config = {
    enabled: true, sizeDp: 100, opacityPercent: 100, moveDurationMs: 100,
    fallbackSizePercent: 60, avoidanceDp: 8, edgeHidePercent: 70,
    edgePreference: "nearest", movementCurve: "ease-out", multiClickMs: 150,
    dragSensitivityPercent: 100,
};
const polynomialCurves = ["minimum-jerk", "asymmetric-smooth-stop"];
let browser;

test.before(async () => {
    const executablePath = [process.env.ANKI_BROWSER_PATH,
        "C:/Program Files/Google/Chrome/Application/chrome.exe",
        "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
        "/usr/bin/chromium", "/usr/bin/google-chrome",
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    ].filter(Boolean).find(filename => fs.existsSync(filename));
    assert.ok(executablePath, "Set ANKI_BROWSER_PATH to an installed Chrome/Edge/Chromium browser");
    browser = await chromium.launch({ executablePath, headless: true });
});
test.after(async () => { await browser?.close(); });

async function setup(t, markup = "", overrides = {}, state = { mode: "hidden", media: -1 }) {
    const page = await browser.newPage({ viewport: { width: 400, height: 600 } });
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    t.after(async () => { await page.close(); assert.deepEqual(errors, []); });
    await page.setContent(`<html><head><style>body{margin:0}#qa{min-height:100px}</style></head><body><div id="qa">${markup}</div></body></html>`);
    await page.evaluate(() => {
        window.__petReports = [];
        window.fetch = async (_url, options) => {
            window.__petReports.push(JSON.parse(options.body));
            return { ok: true };
        };
        // Preserve the production closed shadow tree while permitting test inspection.
        const attachShadow = Element.prototype.attachShadow;
        Element.prototype.attachShadow = function (options) {
            const shadow = attachShadow.call(this, options);
            if (this.id === "ankidroid-reviewer-pet") window.__petShadow = shadow;
            return shadow;
        };
        const now = performance.now.bind(performance);
        window.__timeOffset = 0;
        Object.defineProperty(performance, "now", { value: () => now() + window.__timeOffset });
        Math.random = () => 0;
        window.__renderQueue = Promise.resolve();
        window._queueAction = callback => { window.__renderQueue = window.__renderQueue.then(callback); };
        window._showQuestion = html => { window._queueAction(() => { document.getElementById("qa").innerHTML = html; }); };
        window._showAnswer = window._showQuestion;
    });
    await page.addScriptTag({ path: path.join(assets, "reviewer-pet-geometry.js") });
    await page.addScriptTag({ path: path.join(assets, "reviewer-pet.js") });
    const payload = { generation: "browser-test", visitId: 1, config: { ...config, ...overrides },
        media: [{ url: image }], state: { x: 0.5, y: 0.5, edge: "right", fallback: false, ...state } };
    await page.evaluate(async payload => {
        window.__payload = payload;
        await reviewerPet.configure(payload);
        reviewerPet.setActive(true);
        // Only bypass the host's quiet-window prerequisite, not animation timing or input logic.
        window.__timeOffset += 3500;
    }, payload);
    return page;
}

async function waitReport(page, action, after = 0) {
    await page.waitForFunction(({ action, after }) => window.__petReports.slice(after).some(report => report.action === action),
        { action, after }, { timeout: 4000 });
    return page.evaluate(({ action, after }) => window.__petReports.slice(after).findLast(report => report.action === action), { action, after });
}

async function countReports(page) { return page.evaluate(() => window.__petReports.length); }
async function snapshot(page) {
    const after = await countReports(page);
    await page.evaluate(() => reviewerPet.snapshot());
    return (await waitReport(page, "snapshot", after)).state;
}
async function appear(page) {
    await page.evaluate(() => reviewerPet.command("appear", 1));
    return (await waitReport(page, "appeared")).state;
}
async function targetCenter(page) {
    return page.evaluate(() => {
        const r = window.__petShadow.querySelector("button").getBoundingClientRect();
        return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
    });
}
async function clickPet(page, count = 1) {
    const p = await targetCenter(page);
    for (let i = 0; i < count; i++) await page.mouse.click(p.x, p.y);
}
async function spriteInfo(page) {
    return page.evaluate(() => {
        const sprite = window.__petShadow?.querySelector(".sprite");
        if (!sprite) return { hidden: true, source: "", x: 0, y: 0, width: 0, height: 0 };
        const r = sprite.getBoundingClientRect();
        return { hidden: sprite.hidden, source: sprite.firstElementChild?.src || "", x: r.x, y: r.y, width: r.width, height: r.height };
    });
}

test("initial state is hidden, empty media stays hidden, and stale commands are rejected", async t => {
    const page = await setup(t);
    assert.equal((await snapshot(page)).mode, "hidden");
    assert.equal((await spriteInfo(page)).hidden, true);
    await page.evaluate(() => reviewerPet.command("appear", 99));
    await waitReport(page, "skipped");
    assert.equal((await spriteInfo(page)).hidden, true);
    await page.evaluate(async () => reviewerPet.configure({ ...window.__payload, media: [] }));
    await waitReport(page, "disabled");
    await page.evaluate(() => reviewerPet.command("appear", 1));
    assert.equal((await snapshot(page)).mode, "hidden");
});

test("one resident pet and its media persist across queued flips and next-card DOM replacements", async t => {
    const page = await setup(t);
    await appear(page);
    const before = await spriteInfo(page);
    await page.evaluate(async () => {
        window.__originalPet = document.getElementById("ankidroid-reviewer-pet");
        for (const [index, fn] of [_showAnswer, _showQuestion, _showAnswer].entries()) {
            fn(`<p>Card side ${index}</p>`);
            await window.__renderQueue;
        }
        reviewerPet.visit(2);
    });
    await page.waitForFunction(() => !window.__petShadow.querySelector(".sprite").hidden);
    const after = await spriteInfo(page);
    assert.equal((await snapshot(page)).mode, "resident");
    assert.equal(after.source, before.source);
    assert.equal(after.hidden, false);
    assert.equal(await page.evaluate(() => document.querySelectorAll("#ankidroid-reviewer-pet").length), 1);
    assert.equal(await page.evaluate(() => document.getElementById("ankidroid-reviewer-pet") === window.__originalPet), true);
    assert.equal(await page.evaluate(() => document.getElementById("qa").contains(window.__originalPet)), false);
});

test("a single click moves after recognition, double click collapses, and triple click dismisses immediately", async t => {
    const page = await setup(t);
    await appear(page);
    const before = await spriteInfo(page);
    await clickPet(page);
    assert.equal((await snapshot(page)).mode, "resident");
    await waitReport(page, "moved");
    const moved = await spriteInfo(page);
    assert.ok(Math.hypot(before.x - moved.x, before.y - moved.y) >= 50);
    await clickPet(page, 2);
    assert.equal((await snapshot(page)).mode, "resident");
    await waitReport(page, "collapsed");
    assert.equal((await snapshot(page)).mode, "collapsed");
    const reportStart = await countReports(page);
    await clickPet(page, 3);
    await waitReport(page, "dismissed", reportStart);
    await waitReport(page, "hidden", reportStart);
    assert.equal((await snapshot(page)).mode, "hidden");
    assert.equal((await spriteInfo(page)).hidden, true);
});

test("every saved movement curve accelerates from rest and slows before arriving", async t => {
    for (const movementCurve of ["linear", "ease-out", "ease-in-out", ...polynomialCurves]) {
        const page = await setup(t, "", { moveDurationMs: 600, movementCurve });
        await appear(page);
        const samples = await page.evaluate(async () => {
            const sprite = window.__petShadow.querySelector(".sprite");
            const read = () => {
                const r = sprite.getBoundingClientRect();
                return { x: r.x, y: r.y, time: performance.now() };
            };
            const after = window.__petReports.length;
            const samples = [read()];
            reviewerPet.command("expand", 1);
            const deadline = performance.now() + 3000;
            while (!window.__petReports.slice(after).some(report => report.action === "moved")) {
                if (performance.now() > deadline) throw new Error("Movement never completed");
                await new Promise(requestAnimationFrame);
                samples.push(read());
            }
            return samples;
        });
        const first = samples[0], last = samples.at(-1);
        const atFraction = fraction => {
            const time = first.time + (last.time - first.time) * fraction;
            const right = samples.findIndex(sample => sample.time >= time);
            if (right <= 0) return first;
            const a = samples[right - 1], b = samples[right];
            const ratio = (time - a.time) / (b.time - a.time);
            return { x: a.x + (b.x - a.x) * ratio, y: a.y + (b.y - a.y) * ratio };
        };
        const points = [first, atFraction(0.25), atFraction(0.5), atFraction(0.75), last];
        const distances = points.slice(1).map((point, index) =>
            Math.hypot(point.x - points[index].x, point.y - points[index].y));
        assert.ok(distances[0] < distances[1] * 0.8, `${movementCurve} started at full speed`);
        assert.ok(distances[3] < distances[2] * 0.8, `${movementCurve} stopped abruptly`);
    }
});

for (const movementCurve of polynomialCurves) {
    test(`${movementCurve} follows its designed velocity profile and stops with low acceleration`, async t => {
        const page = await setup(t, "", { moveDurationMs: 1000, movementCurve });
        await appear(page);
        const samples = await page.evaluate(() => {
            const sprite = window.__petShadow.querySelector(".sprite");
            reviewerPet.command("expand", 1);
            const motion = sprite.getAnimations()[0];
            if (!motion) throw new Error("Movement did not start");
            motion.pause();
            const duration = motion.effect.getTiming().duration;
            const samples = [];
            for (let step = 0; step <= 100; step++) {
                motion.currentTime = duration * step / 100;
                const style = getComputedStyle(sprite);
                samples.push({ x: parseFloat(style.left), y: parseFloat(style.top) });
            }
            motion.finish();
            return { duration, samples };
        });
        assert.equal(samples.duration, 1000);
        const first = samples.samples[0], last = samples.samples.at(-1);
        const dx = last.x - first.x, dy = last.y - first.y;
        const distanceSquared = dx * dx + dy * dy;
        assert.ok(distanceSquared > 100, "The verification move must cover a measurable distance");
        const progress = samples.samples.map(point =>
            ((point.x - first.x) * dx + (point.y - first.y) * dy) / distanceSquared);
        assert.ok(progress.every((value, index) => index === 0 || value >= progress[index - 1] - 1e-5));
        const velocities = progress.slice(1, -1).map((_value, index) =>
            (progress[index + 2] - progress[index]) / 0.02);
        const peakSpeed = Math.max(...velocities);
        const peakTime = (velocities.indexOf(peakSpeed) + 1) / 100;
        const asymmetric = movementCurve === "asymmetric-smooth-stop";
        assert.ok(Math.abs(peakSpeed - (asymmetric ? 2 : 1.875)) < 0.03, `Unexpected peak speed ${peakSpeed}`);
        assert.ok(Math.abs(peakTime - (asymmetric ? 0.4 : 0.5)) < 0.021, `Unexpected peak time ${peakTime}`);
        const initialAcceleration = (progress[2] - 2 * progress[1] + progress[0]) / 0.01 ** 2;
        const finalAcceleration = (progress[100] - 2 * progress[99] + progress[98]) / 0.01 ** 2;
        assert.ok(Math.abs(finalAcceleration) < 1, `The finish acceleration remained high: ${finalAcceleration}`);
        if (asymmetric) {
            assert.ok(initialAcceleration > 2, "The asymmetric curve should accelerate promptly from rest");
            assert.ok(progress[50] > 0.65 && progress[50] < 0.67);
        } else {
            assert.ok(Math.abs(initialAcceleration) < 1, "Minimum jerk should start with low acceleration");
            assert.ok(Math.abs(progress[50] - 0.5) < 0.001);
        }
    });

    test(`${movementCurve} applies to both flight legs and the dismissal transform and opacity`, async t => {
        const page = await setup(t, "", { moveDurationMs: 1000, movementCurve });
        await appear(page);
        await page.evaluate(() => reviewerPet.command("fly", 1));
        await waitReport(page, "flightStarted");
        const flight = await page.evaluate(() => {
            const sprite = window.__petShadow.querySelector(".sprite");
            const motion = sprite.getAnimations()[0];
            motion.pause();
            const duration = motion.effect.getTiming().duration;
            const points = [0, 0.3, 0.6, 0.8, 1].map(fraction => {
                motion.currentTime = duration * fraction;
                const style = getComputedStyle(sprite);
                return { x: parseFloat(style.left), y: parseFloat(style.top) };
            });
            motion.finish();
            return { duration, points };
        });
        assert.equal(flight.duration, 2000);
        const midpointProgress = (start, middle, end) => {
            const dx = end.x - start.x, dy = end.y - start.y;
            assert.ok(dx * dx + dy * dy > 100);
            return ((middle.x - start.x) * dx + (middle.y - start.y) * dy) / (dx * dx + dy * dy);
        };
        const outbound = midpointProgress(...flight.points.slice(0, 3));
        const inbound = midpointProgress(...flight.points.slice(2));
        const expectedMidpoint = movementCurve === "minimum-jerk" ? 0.5 : 0.658877;
        assert.ok(Math.abs(outbound - expectedMidpoint) < 0.001, `Unexpected outbound progress ${outbound}`);
        assert.ok(Math.abs(inbound - expectedMidpoint) < 0.001, `Unexpected inbound progress ${inbound}`);
        await waitReport(page, "flightEnded");
        await clickPet(page, 3);
        await waitReport(page, "dismissed");
        const dismissal = await page.evaluate(() => {
            const sprite = window.__petShadow.querySelector(".sprite");
            const motion = sprite.getAnimations()[0];
            motion.pause();
            const duration = motion.effect.getTiming().duration;
            motion.currentTime = duration / 2;
            const style = getComputedStyle(sprite);
            const result = { duration, progress: 1 - parseFloat(style.opacity),
                translation: -new DOMMatrixReadOnly(style.transform).m42 / 30 };
            motion.finish();
            return result;
        });
        assert.equal(dismissal.duration, 600);
        assert.ok(Math.abs(dismissal.progress - expectedMidpoint) < 0.001);
        assert.ok(Math.abs(dismissal.translation - expectedMidpoint) < 0.001);
        assert.equal((await snapshot(page)).mode, "hidden");
    });
}

test("border dragging follows vertically, stays tucked, and does not turn release into expansion", async t => {
    const page = await setup(t);
    await appear(page);
    await clickPet(page, 2);
    await waitReport(page, "collapsed");
    const initial = await spriteInfo(page), center = await targetCenter(page);
    const after = await countReports(page);
    await page.mouse.move(center.x, center.y);
    await page.mouse.down();
    await page.mouse.move(center.x, center.y + 70, { steps: 5 });
    await page.mouse.up();
    await waitReport(page, "dragged", after);
    await page.waitForFunction(() => window.__petShadow.querySelector(".sprite").getAnimations()
        .every(animation => animation.playState !== "running"));
    const dragged = await spriteInfo(page);
    assert.equal((await snapshot(page)).mode, "collapsed");
    assert.equal(dragged.x, initial.x);
    assert.ok(Math.abs(dragged.y - initial.y - 70) < 1);
    assert.equal(await page.evaluate(() => scrollY), 0);
    assert.equal(await page.evaluate(after => window.__petReports.slice(after).some(r => r.action === "moved"), after), false);
    await clickPet(page);
    await waitReport(page, "moved", after);
    assert.equal((await snapshot(page)).mode, "resident");
});

test("a large border drag starts smoothly and finishes at the pointer without a frame jump", async t => {
    const page = await setup(t);
    await appear(page);
    await clickPet(page, 2);
    await waitReport(page, "collapsed");
    const initial = await spriteInfo(page);
    const center = await targetCenter(page);
    await page.mouse.move(center.x, center.y);
    await page.mouse.down();
    const sampling = page.evaluate(async () => {
        const sprite = window.__petShadow.querySelector(".sprite");
        const read = () => {
            const r = sprite.getBoundingClientRect();
            return { x: r.x, y: r.y, time: performance.now(), hidden: sprite.hidden };
        };
        const samples = [read()];
        window.__dragSamplingDone = false;
        const deadline = performance.now() + 4000;
        while (!window.__dragSamplingDone || sprite.getAnimations().some(animation => animation.playState === "running")) {
            if (performance.now() > deadline) throw new Error("Border drag did not settle");
            await new Promise(requestAnimationFrame);
            samples.push(read());
        }
        return samples;
    });
    await page.mouse.move(center.x, center.y + 120);
    await page.mouse.up();
    await page.evaluate(() => { window.__dragSamplingDone = true; });
    const samples = await sampling;
    const distances = samples.slice(1).map((sample, index) =>
        Math.hypot(sample.x - samples[index].x, sample.y - samples[index].y));
    assert.ok(samples.every(sample => !sample.hidden));
    assert.ok(distances.every((distance, index) =>
        distance < Math.max(48, 2 * (samples[index + 1].time - samples[index].time))),
        `drag started with a ${Math.max(...distances).toFixed(1)}px jump`);
    assert.ok(Math.abs(samples.at(-1).y - initial.y - 120) < 1,
        `drag ended at ${samples.at(-1).y.toFixed(2)} instead of ${(initial.y + 120).toFixed(2)}`);
    assert.equal((await snapshot(page)).mode, "collapsed");
});

test("resident and tucked pets stay visible throughout continuous card scrolling", async t => {
    const page = await setup(t, '<p>Scrollable card</p><button style="position:absolute;left:0;top:500px;width:400px;height:60px">Play</button><div style="height:2400px"></div>');
    await appear(page);
    const original = await spriteInfo(page);
    for (const mode of ["resident", "collapsed"]) {
        if (mode === "collapsed") {
            await clickPet(page, 2);
            await waitReport(page, "collapsed");
        }
        const samples = await page.evaluate(async () => {
            const samples = [];
            for (let i = 0; i < 12; i++) {
                window.scrollBy(0, 15);
                await new Promise(requestAnimationFrame);
                await new Promise(requestAnimationFrame);
                const sprite = window.__petShadow.querySelector(".sprite");
                const r = window.__petShadow.querySelector("button").getBoundingClientRect();
                const hit = { x: r.x, y: r.y, width: r.width, height: r.height };
                const bounds = { x: 0, y: 0, width: innerWidth, height: innerHeight };
                const controls = reviewerPetGeometry.collect(document.body, bounds).controls;
                samples.push({ hidden: sprite.hidden, source: sprite.firstElementChild.src,
                    safe: reviewerPetGeometry.safe(hit, bounds, controls, 8) });
            }
            return samples;
        });
        assert.ok(samples.every(sample => !sample.hidden), `${mode} disappeared while the card was scrolling`);
        assert.ok(samples.every(sample => sample.source === original.source));
        await page.waitForFunction(() => window.__petShadow.querySelector(".sprite").getAnimations()
            .every(animation => animation.playState !== "running"));
        assert.equal((await snapshot(page)).mode, mode);
        assert.equal(await page.evaluate(() => {
            const r = window.__petShadow.querySelector("button").getBoundingClientRect();
            const bounds = { x: 0, y: 0, width: innerWidth, height: innerHeight };
            return reviewerPetGeometry.safe({ x: r.x, y: r.y, width: r.width, height: r.height },
                bounds, reviewerPetGeometry.collect(document.body, bounds).controls, 8);
        }), true, `${mode} still blocked a control after settling`);
    }
    assert.ok(await page.evaluate(() => scrollY > 0));
    assert.equal(await page.evaluate(() => document.querySelectorAll("#ankidroid-reviewer-pet").length), 1);
});

for (const mode of ["resident", "collapsed"]) {
    test(`scroll avoidance moves the ${mode} pet smoothly without teleporting`, async t => {
        const page = await setup(t,
            '<button style="position:absolute;left:0;top:420px;width:400px;height:70px">Play</button><div style="height:2400px"></div>',
            { moveDurationMs: 500, movementCurve: "linear" });
        await appear(page);
        if (mode === "collapsed") {
            await clickPet(page, 2);
            await waitReport(page, "collapsed");
        }
        const samples = await page.evaluate(async () => {
            const sprite = window.__petShadow.querySelector(".sprite");
            const read = () => {
                const r = sprite.getBoundingClientRect();
                return { x: r.x, y: r.y, time: performance.now(), hidden: sprite.hidden, source: sprite.firstElementChild.src };
            };
            const samples = [read()];
            window.scrollTo(0, 150);
            const until = performance.now() + 800;
            while (performance.now() < until) {
                await new Promise(requestAnimationFrame);
                samples.push(read());
            }
            return samples;
        });
        const distances = samples.slice(1).map((sample, index) =>
            Math.hypot(sample.x - samples[index].x, sample.y - samples[index].y));
        assert.ok(samples.every(sample => !sample.hidden), `${mode} disappeared while moving`);
        assert.ok(samples.every(sample => sample.source === samples[0].source));
        assert.ok(Math.hypot(samples.at(-1).x - samples[0].x, samples.at(-1).y - samples[0].y) >= 30,
            `${mode} did not move away from the incoming control`);
        assert.ok(distances.every((distance, index) =>
            distance < Math.max(48, 2 * (samples[index + 1].time - samples[index].time))),
            `${mode} jumped ${Math.max(...distances).toFixed(1)}px in one frame`);
        assert.equal((await snapshot(page)).mode, mode);
    });
}

test("a small amount of ordinary content scrolling beneath the pet does not force a relocation", async t => {
    const page = await setup(t,
        '<svg style="position:absolute;left:150px;top:370px;width:100px;height:10px"><rect width="100" height="10" fill="gray"/></svg><div style="height:2400px"></div>');
    await appear(page);
    const before = await spriteInfo(page);
    await page.evaluate(() => window.scrollTo(0, 30));
    // Permit the scroll quiet period and any unnecessary movement to finish.
    await page.waitForTimeout(450);
    const after = await spriteInfo(page);
    assert.equal(after.hidden, false);
    assert.equal(after.source, before.source);
    assert.ok(Math.hypot(after.x - before.x, after.y - before.y) < 1,
        "a small ordinary-content overlap needlessly displaced the pet");
});

test("pet interactions suppress selection and context menus while card text remains selectable", async t => {
    const page = await setup(t, '<p id="card-text" style="user-select:text">Selectable card text</p>');
    await appear(page);
    const canceled = await page.evaluate(() => {
        const host = document.getElementById("ankidroid-reviewer-pet");
        const target = window.__petShadow.querySelector("button");
        const canceled = {};
        for (const type of ["selectstart", "contextmenu", "dragstart"]) {
            const options = { bubbles: true, cancelable: true, composed: true };
            canceled[type] = !target.dispatchEvent(new Event(type, options));
            canceled[`${type}OnCard`] = !document.getElementById("card-text").dispatchEvent(new Event(type, options));
        }
        canceled.hostSelectable = getComputedStyle(host).userSelect !== "none";
        canceled.targetSelectable = getComputedStyle(target).userSelect !== "none";
        canceled.tapHighlight = getComputedStyle(target).webkitTapHighlightColor;
        return canceled;
    });
    assert.deepEqual(canceled, {
        selectstart: true, selectstartOnCard: false,
        contextmenu: true, contextmenuOnCard: false,
        dragstart: true, dragstartOnCard: false,
        hostSelectable: false, targetSelectable: false, tapHighlight: "rgba(0, 0, 0, 0)",
    });
    await page.locator("#card-text").dblclick({ position: { x: 25, y: 8 } });
    assert.ok(await page.evaluate(() => document.getSelection().toString().includes("Selectable")));
    await clickPet(page, 2);
    await waitReport(page, "collapsed");
    assert.equal((await snapshot(page)).mode, "collapsed");
});

test("input focus and a paused host hide the sprite; resume restores the same resident", async t => {
    const page = await setup(t, '<input id="answer" style="position:fixed;left:10px;top:10px;width:100px;height:30px">');
    await appear(page);
    const original = await spriteInfo(page);
    await page.focus("#answer");
    await page.waitForFunction(() => window.__petShadow.querySelector(".sprite").hidden);
    assert.equal((await snapshot(page)).mode, "resident");
    await page.evaluate(() => document.getElementById("answer").blur());
    await page.waitForFunction(() => !window.__petShadow.querySelector(".sprite").hidden);
    await page.evaluate(() => reviewerPet.setActive(false));
    await waitReport(page, "paused");
    assert.equal((await spriteInfo(page)).hidden, true);
    await page.evaluate(() => { reviewerPet.setActive(true); window.__timeOffset += 3500; });
    await page.waitForFunction(() => !window.__petShadow.querySelector(".sprite").hidden);
    assert.equal((await spriteInfo(page)).source, original.source);
});

test("ordinary content can force the smaller fallback, but an audio button blocks that fallback", async t => {
    const fullContent = '<svg id="content" style="position:fixed;inset:0;width:400px;height:600px"><rect width="400" height="600" fill="gray"/></svg>';
    const page = await setup(t, fullContent);
    await appear(page);
    const placed = await spriteInfo(page);
    assert.equal((await snapshot(page)).fallback, true);
    assert.equal(placed.width, 60);
    assert.equal(placed.x, 332);
    assert.equal(placed.y, 532);
    await page.evaluate(() => {
        const audio = document.createElement("a");
        audio.className = "replay-button";
        audio.style.cssText = "position:fixed;right:0;bottom:0;width:70px;height:70px";
        audio.textContent = "Play";
        document.getElementById("qa").append(audio);
    });
    await page.waitForFunction(() => window.__petShadow.querySelector("button").hidden);
    assert.equal((await snapshot(page)).mode, "resident");
    assert.equal((await spriteInfo(page)).hidden, false);
    assert.equal((await spriteInfo(page)).source, placed.source);
});

test("fly command uses the existing resident and resumes it; hidden fly ends hidden", async t => {
    const page = await setup(t);
    await appear(page);
    const resident = await spriteInfo(page);
    const after = await countReports(page);
    await page.evaluate(() => reviewerPet.command("fly", 1));
    await waitReport(page, "flightStarted", after);
    await waitReport(page, "flightEnded", after);
    assert.equal((await snapshot(page)).mode, "resident");
    assert.equal((await spriteInfo(page)).source, resident.source);
    await page.evaluate(async () => reviewerPet.configure({ ...window.__payload, generation: "hidden-flight", state: { mode: "hidden", media: -1 } }));
    const hiddenStart = await countReports(page);
    await page.evaluate(() => reviewerPet.command("fly", 1));
    await waitReport(page, "flightStarted", hiddenStart);
    await waitReport(page, "flightEnded", hiddenStart);
    assert.equal((await snapshot(page)).mode, "hidden");
    assert.equal((await spriteInfo(page)).hidden, true);
});

for (const mode of ["resident", "collapsed"]) {
    test(`the ${mode} pet takes off and lands without jumping at either flight boundary`, async t => {
        const page = await setup(t, "", { moveDurationMs: 500 });
        await appear(page);
        if (mode === "collapsed") {
            await clickPet(page, 2);
            await waitReport(page, "collapsed");
        }
        const samples = await page.evaluate(async () => {
            const sprite = window.__petShadow.querySelector(".sprite");
            const read = () => {
                const r = sprite.getBoundingClientRect();
                return { x: r.x, y: r.y, time: performance.now(), hidden: sprite.hidden };
            };
            const after = window.__petReports.length;
            const samples = [read()];
            reviewerPet.command("fly", 1);
            await Promise.resolve();
            samples.push(read());
            const deadline = performance.now() + 4000;
            while (!window.__petReports.slice(after).some(report => report.action === "flightEnded")) {
                if (performance.now() > deadline) throw new Error("The flight never completed");
                await new Promise(requestAnimationFrame);
                samples.push(read());
            }
            await new Promise(requestAnimationFrame);
            samples.push(read());
            return samples;
        });
        const distances = samples.slice(1).map((sample, index) =>
            Math.hypot(sample.x - samples[index].x, sample.y - samples[index].y));
        assert.ok(samples.every(sample => !sample.hidden), `${mode} vanished during its flight`);
        assert.ok(distances.every((distance, index) =>
            distance < Math.max(48, 2 * (samples[index + 1].time - samples[index].time))),
            `${mode} jumped ${Math.max(...distances).toFixed(1)}px while taking off or returning`);
        assert.equal((await snapshot(page)).mode, mode);
    });
}

test("a noninteractive flight can cross a card with no completely clear horizontal corridor", async t => {
    const controls = Array.from({ length: 12 }, (_, index) =>
        `<button style="position:absolute;left:160px;top:${index * 50}px;width:80px;height:40px">Replay ${index}</button>`).join("");
    const page = await setup(t, controls, { moveDurationMs: 300 });
    const after = await countReports(page);
    await page.evaluate(() => reviewerPet.command("fly", 1));
    await waitReport(page, "flightStarted", after);
    assert.equal((await spriteInfo(page)).hidden, false);
    assert.equal(await page.evaluate(() => window.__petShadow.querySelector("button").hidden), true);
    await waitReport(page, "flightEnded", after);
    assert.equal((await snapshot(page)).mode, "hidden");
});

test("a flight stays visible during card layout changes and scrolling", async t => {
    const page = await setup(t, '<div style="height:2400px"></div>', { moveDurationMs: 500 });
    const after = await countReports(page);
    await page.evaluate(() => reviewerPet.command("fly", 1));
    await waitReport(page, "flightStarted", after);
    const samples = await page.evaluate(async () => {
        const samples = [];
        for (let i = 0; i < 8; i++) {
            document.getElementById("qa").style.paddingTop = `${i}px`;
            window.scrollBy(0, 15);
            await new Promise(requestAnimationFrame);
            samples.push({ hidden: window.__petShadow.querySelector(".sprite").hidden,
                interactive: !window.__petShadow.querySelector("button").hidden });
        }
        return samples;
    });
    assert.ok(samples.every(sample => !sample.hidden), "layout refresh hid the running flight");
    assert.ok(samples.every(sample => !sample.interactive));
    await waitReport(page, "flightEnded", after);
    assert.equal((await snapshot(page)).mode, "hidden");
    assert.equal((await spriteInfo(page)).hidden, true);
});

test("DOM geometry protects replay variants and measures ruby and MathJax as complete content", async t => {
    const page = await setup(t, '<div style="padding:20px"><a class="replaybutton">Replay</a><span class="soundLink">Sound</span><span role="link">Link</span><ruby style="display:inline-block">字<rt>じ</rt></ruby><mjx-container style="display:inline-block;width:120px;height:70px">Formula</mjx-container></div>');
    const result = await page.evaluate(() => {
        const collected = reviewerPetGeometry.collect(document.getElementById("qa"), { x: 0, y: 0, width: 400, height: 600 });
        const rect = selector => {
            const r = document.querySelector(selector).getBoundingClientRect();
            return { x: r.x, y: r.y, width: r.width, height: r.height };
        };
        const covers = (list, r) => list.some(item => item.x <= r.x && item.y <= r.y && item.x + item.width >= r.x + r.width && item.y + item.height >= r.y + r.height);
        return {
            replay: covers(collected.controls, rect(".replaybutton")),
            sound: covers(collected.controls, rect(".soundLink")),
            link: covers(collected.controls, rect("[role=link]")),
            ruby: covers(collected.content, rect("ruby")),
            math: covers(collected.content, rect("mjx-container")),
        };
    });
    assert.deepEqual(result, { replay: true, sound: true, link: true, ruby: true, math: true });
});

test("imported video is muted, loops, and pauses and resumes with the host", async t => {
    const page = await setup(t);
    // Reuse the repository's valid 320x240 H.264 fixture; no default pet media is bundled.
    const fixture = path.resolve(__dirname, "../../AnkiDroid/src/androidTest/assets/anki-15872-valid-1.mp4");
    const source = "data:video/mp4;base64," + fs.readFileSync(fixture).toString("base64");
    await page.evaluate(async url => reviewerPet.configure({ ...window.__payload, media: [{ url, mimeType: "video/mp4" }] }), source);
    try { await appear(page); } catch (error) {
        const diagnostic = await page.evaluate(() => {
            const video = window.__petShadow.querySelector("video");
            return { reports: window.__petReports, video: video && { readyState: video.readyState,
                networkState: video.networkState, width: video.videoWidth, error: video.error?.message } };
        });
        assert.fail(`${error.message}\n${JSON.stringify(diagnostic)}`);
    }
    const video = await page.evaluate(() => {
        const element = window.__petShadow.querySelector("video");
        return { present: !!element, width: element?.videoWidth, muted: element?.muted,
            loop: element?.loop, inline: element?.playsInline, controls: element?.controls, paused: element?.paused };
    });
    assert.deepEqual(video, { present: true, width: 320, muted: true, loop: true, inline: true, controls: false, paused: false });
    await page.evaluate(() => reviewerPet.setActive(false));
    assert.equal(await page.evaluate(() => window.__petShadow.querySelector("video").paused), true);
    await page.evaluate(() => reviewerPet.setActive(true));
    await page.waitForFunction(() => !window.__petShadow.querySelector("video").paused);
});

test("replacing a disabled or deleted current media file updates the resident source", async t => {
    const page = await setup(t);
    await appear(page);
    const replacement = "data:image/svg+xml," + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="80" height="80"><rect width="80" height="80" fill="green"/></svg>');
    await page.evaluate(async url => reviewerPet.configure({ ...window.__payload, media: [{ url }] }), replacement);
    assert.equal((await snapshot(page)).mode, "resident");
    assert.equal((await spriteInfo(page)).source, replacement);
});

test("replacing media during movement preserves the drawn position until the new image decodes", async t => {
    const page = await setup(t, "", { moveDurationMs: 600 });
    await appear(page);
    const replacement = "data:image/svg+xml," + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="100" height="100" fill="green"/></svg>');
    const result = await page.evaluate(async url => {
        const sprite = window.__petShadow.querySelector(".sprite");
        const read = () => {
            const r = sprite.getBoundingClientRect();
            return { x: r.x, y: r.y, hidden: sprite.hidden, source: sprite.firstElementChild.src };
        };
        reviewerPet.command("expand", 1);
        await new Promise(resolve => setTimeout(resolve, 120));
        const before = read();
        const decode = HTMLImageElement.prototype.decode;
        const delayed = new Promise(resolve => { window.__releaseReplacement = resolve; });
        HTMLImageElement.prototype.decode = async function () {
            await delayed;
            return decode.call(this);
        };
        window.__replacementConfigure = reviewerPet.configure({ ...window.__payload, media: [{ url }] });
        await Promise.resolve();
        const immediate = read();
        await new Promise(requestAnimationFrame);
        const waiting = read();
        window.__releaseReplacement();
        await window.__replacementConfigure;
        return { before, immediate, waiting };
    }, replacement);
    for (const sample of [result.immediate, result.waiting]) {
        assert.equal(sample.hidden, false);
        assert.equal(sample.source, result.before.source, "the old decoded image should remain visible while loading its replacement");
        assert.ok(Math.hypot(sample.x - result.before.x, sample.y - result.before.y) < 24,
            "reconfiguration snapped the visible animation to its saved destination");
    }
    assert.equal((await spriteInfo(page)).source, replacement);
});

test("a viewport offset change during movement freezes the live position before retargeting", async t => {
    const page = await setup(t, "", { moveDurationMs: 600 });
    await appear(page);
    const samples = await page.evaluate(async () => {
        const sprite = window.__petShadow.querySelector(".sprite");
        const read = () => {
            const r = sprite.getBoundingClientRect();
            return { x: r.x, y: r.y, time: performance.now(), hidden: sprite.hidden };
        };
        reviewerPet.command("expand", 1);
        await new Promise(resolve => setTimeout(resolve, 120));
        const samples = [read()];
        Object.defineProperties(window.visualViewport, {
            offsetLeft: { configurable: true, get: () => 100 },
            offsetTop: { configurable: true, get: () => 80 },
        });
        window.dispatchEvent(new Event("resize"));
        for (let frame = 0; frame < 24; frame++) {
            await new Promise(requestAnimationFrame);
            samples.push(read());
        }
        return samples;
    });
    const distances = samples.slice(1).map((sample, index) =>
        Math.hypot(sample.x - samples[index].x, sample.y - samples[index].y));
    assert.ok(samples.every(sample => !sample.hidden));
    assert.ok(distances.every((distance, index) =>
        distance < Math.max(48, 2 * (samples[index + 1].time - samples[index].time))),
        `viewport change caused a ${Math.max(...distances).toFixed(1)}px jump`);
});

test("root zoom changes keep the resident sprite at the same physical position and size", async t => {
    const page = await setup(t);
    await appear(page);
    const original = await spriteInfo(page);
    const samples = await page.evaluate(async () => {
        const sprite = window.__petShadow.querySelector(".sprite");
        const samples = [];
        for (const zoom of [1.5, 0.8, 1]) {
            document.documentElement.style.zoom = String(zoom);
            window.dispatchEvent(new Event("resize"));
            for (let frame = 0; frame < 8; frame++) {
                await new Promise(requestAnimationFrame);
                const r = sprite.getBoundingClientRect();
                samples.push({ x: r.x, y: r.y, width: r.width, height: r.height, hidden: sprite.hidden });
            }
        }
        return samples;
    });
    assert.ok(samples.every(sample => !sample.hidden));
    assert.ok(samples.every(sample => Math.hypot(sample.x - original.x, sample.y - original.y) < 1),
        "updating the inverse host zoom displaced the pet");
    assert.ok(samples.every(sample => Math.abs(sample.width - original.width) < 1 &&
        Math.abs(sample.height - original.height) < 1), "root zoom changed the physical pet size");
});

async function delayMediaDecode(page) {
    await page.evaluate(() => {
        const decode = HTMLImageElement.prototype.decode;
        const delayed = new Promise(resolve => { window.__releaseDecode = resolve; });
        HTMLImageElement.prototype.decode = async function () {
            window.__decodeEntered = true;
            await delayed;
            const result = await decode.call(this);
            window.__decodeFinished = true;
            return result;
        };
        reviewerPet.command("appear", 1);
    });
    await page.waitForFunction(() => window.__decodeEntered);
}

test("an old visit's delayed hidden appearance cannot wake the next card", async t => {
    const page = await setup(t);
    await delayMediaDecode(page);
    await page.evaluate(() => { reviewerPet.visit(2); window.__releaseDecode(); });
    await page.waitForFunction(() => window.__decodeFinished);
    await page.waitForTimeout(250);
    assert.equal((await snapshot(page)).mode, "hidden");
    assert.equal((await spriteInfo(page)).hidden, true);
});

test("turning pets off cancels a delayed appearance even if enabled again before media finishes", async t => {
    const page = await setup(t);
    await delayMediaDecode(page);
    await page.evaluate(async () => {
        await reviewerPet.configure({ ...window.__payload, config: { ...window.__payload.config, enabled: false } });
        await reviewerPet.configure(window.__payload);
        window.__releaseDecode();
    });
    await page.waitForFunction(() => window.__decodeFinished);
    await page.waitForTimeout(250);
    assert.equal((await snapshot(page)).mode, "hidden");
    assert.equal((await spriteInfo(page)).hidden, true);
});

test("pausing the host cancels a pending decode even after a quick resume", async t => {
    const page = await setup(t);
    await delayMediaDecode(page);
    await page.evaluate(() => {
        reviewerPet.setActive(false);
        reviewerPet.setActive(true);
        window.__timeOffset += 3500;
        window.__releaseDecode();
    });
    await page.waitForFunction(() => window.__decodeFinished);
    await page.waitForTimeout(250);
    assert.equal((await snapshot(page)).mode, "hidden");
    assert.equal((await spriteInfo(page)).hidden, true);
});

for (const tag of ["audio", "video"]) {
    test(`a card's raw ${tag} playback reports occupancy and ${tag === "audio" ? "keeps the pet visible" : "hides the pet until paused"}`, async t => {
        const page = await setup(t, `<${tag} id="card-media" muted loop style="width:150px;height:100px"></${tag}>`);
        await appear(page);
        const fixture = path.resolve(__dirname, "../../AnkiDroid/src/androidTest/assets/anki-15872-valid-1.mp4");
        const source = "data:video/mp4;base64," + fs.readFileSync(fixture).toString("base64");
        const after = await countReports(page);
        await page.evaluate(async url => {
            const element = document.getElementById("card-media");
            element.src = url;
            await element.play();
        }, source);
        await page.waitForFunction(after => window.__petReports.slice(after).some(r => r.action === "occupancy" && r.busy), after);
        if (tag === "video") {
            await page.waitForFunction(() => window.__petShadow.querySelector(".sprite").hidden);
        } else {
            const visibleFrames = await page.evaluate(async () => {
                const samples = [];
                for (let frame = 0; frame < 12; frame++) {
                    await new Promise(requestAnimationFrame);
                    samples.push(!window.__petShadow.querySelector(".sprite").hidden);
                }
                return samples;
            });
            assert.ok(visibleFrames.every(Boolean), "card audio briefly hid the resident pet");
        }
        assert.equal((await snapshot(page)).mode, "resident");
        const pausedAt = await countReports(page);
        await page.evaluate(() => document.getElementById("card-media").pause());
        await page.waitForFunction(after => window.__petReports.slice(after).some(r => r.action === "occupancy" && !r.busy), pausedAt);
        await page.waitForFunction(() => !window.__petShadow.querySelector(".sprite").hidden);
        assert.equal((await snapshot(page)).mode, "resident");
    });
}

test("card audio and queued side changes preserve the resident sprite on every rendered frame", async t => {
    const page = await setup(t);
    await appear(page);
    const original = await spriteInfo(page);
    const fixture = path.resolve(__dirname, "../../AnkiDroid/src/androidTest/assets/anki-15872-valid-1.mp4");
    const source = "data:video/mp4;base64," + fs.readFileSync(fixture).toString("base64");
    const samples = await page.evaluate(async url => {
        const host = document.getElementById("ankidroid-reviewer-pet");
        const sprite = window.__petShadow.querySelector(".sprite");
        const samples = [];
        const sample = () => samples.push({ hidden: sprite.hidden, source: sprite.firstElementChild.src,
            sameHost: document.getElementById("ankidroid-reviewer-pet") === host });
        for (const [index, show] of [_showAnswer, _showQuestion, _showAnswer].entries()) {
            // The real backend hides #qa during asynchronous replacement and MathJax work.
            // The application-owned host must remain visible through that interval.
            _queueAction(async () => {
                document.getElementById("qa").style.opacity = "0";
                await new Promise(requestAnimationFrame);
                sample();
            });
            show(`<p>Card side ${index}</p><audio id="card-audio" muted loop></audio>`);
            await window.__renderQueue;
            document.getElementById("qa").style.opacity = "1";
            if (index === 1) reviewerPet.visit(2);
            const audio = document.getElementById("card-audio");
            audio.src = url;
            await audio.play();
            for (let frame = 0; frame < 12; frame++) {
                await new Promise(requestAnimationFrame);
                sample();
            }
            audio.pause();
            await new Promise(requestAnimationFrame);
            sample();
        }
        return samples;
    }, source);
    assert.ok(samples.every(sample => !sample.hidden), "the resident vanished during a card side change or its audio");
    assert.ok(samples.every(sample => sample.source === original.source));
    assert.ok(samples.every(sample => sample.sameHost));
    assert.equal((await snapshot(page)).mode, "resident");
});

test("a recreated WebView restores tucked media, edge and relative height after resize", async t => {
    const page = await setup(t, "", {}, { mode: "collapsed", media: 0, edge: "left", y: 0.4 });
    await page.waitForFunction(() => !window.__petShadow.querySelector(".sprite").hidden);
    const restored = await spriteInfo(page);
    assert.equal((await snapshot(page)).mode, "collapsed");
    assert.equal(restored.x, -70);
    assert.equal(restored.y, 200);
    assert.equal(restored.source, image);
    await page.setViewportSize({ width: 320, height: 400 });
    await page.waitForFunction(() => {
        const sprite = window.__petShadow.querySelector(".sprite"), r = sprite.getBoundingClientRect();
        return !sprite.hidden && Math.abs(r.x + 70) < 1 && Math.abs(r.y - 120) < 1 &&
            sprite.getAnimations().every(animation => animation.playState !== "running");
    });
    const resized = await spriteInfo(page);
    assert.equal(resized.x, -70);
    assert.equal(resized.y, 120);
    const state = await snapshot(page);
    assert.equal(state.mode, "collapsed");
    assert.equal(state.edge, "left");
    assert.equal(state.y, 0.4);
});

test("enabling pets synchronizes occupancy of card media already playing before host creation", async t => {
    const page = await setup(t, '<video id="card-media" muted loop style="width:150px;height:100px"></video>', { enabled: false });
    assert.equal(await page.evaluate(() => document.getElementById("ankidroid-reviewer-pet")), null);
    const fixture = path.resolve(__dirname, "../../AnkiDroid/src/androidTest/assets/anki-15872-valid-1.mp4");
    const source = "data:video/mp4;base64," + fs.readFileSync(fixture).toString("base64");
    await page.evaluate(async url => {
        const video = document.getElementById("card-media");
        video.src = url;
        await video.play();
    }, source);
    const after = await countReports(page);
    await page.evaluate(async () => reviewerPet.configure({ ...window.__payload,
        config: { ...window.__payload.config, enabled: true } }));
    const rendered = await waitReport(page, "rendered", after);
    assert.equal(rendered.visitId, 1);
    assert.equal(rendered.busy, true);
    assert.equal((await spriteInfo(page)).hidden, true);
    await page.evaluate(() => document.getElementById("card-media").pause());
    const paused = await waitReport(page, "occupancy", after);
    assert.equal(paused.busy, false);
});

test("the smallest fallback keeps a usable target while leaving inputs safe", async t => {
    const fullContent = '<svg style="position:fixed;inset:0;width:400px;height:600px"><rect width="400" height="600" fill="gray"/></svg>';
    const page = await setup(t, fullContent + '<input style="position:fixed;left:10px;top:10px;width:80px;height:20px">',
        { sizeDp: 32, fallbackSizePercent: 25 });
    await appear(page);
    const sprite = await spriteInfo(page);
    assert.equal(sprite.width, 8);
    assert.equal(sprite.height, 8);
    const target = await page.evaluate(() => {
        const r = window.__petShadow.querySelector("button").getBoundingClientRect();
        const hit = { x: r.x, y: r.y, width: r.width, height: r.height };
        const bounds = { x: 0, y: 0, width: 400, height: 600 };
        const collected = reviewerPetGeometry.collect(document.body, bounds);
        return { ...hit, safe: reviewerPetGeometry.safe(hit, bounds, collected.controls, 8) };
    });
    assert.deepEqual(target, { x: 368, y: 568, width: 24, height: 24, safe: true });
    await clickPet(page, 2);
    await waitReport(page, "collapsed");
    const docked = await spriteInfo(page);
    assert.equal(docked.width, 8);
    assert.equal(docked.height, 8);
    assert.equal((await snapshot(page)).fallback, true);
    const dockTarget = await page.evaluate(() => {
        const r = window.__petShadow.querySelector("button").getBoundingClientRect();
        return { width: r.width, height: r.height };
    });
    assert.deepEqual(dockTarget, { width: 24, height: 24 });
    const center = await targetCenter(page), draggedAt = await countReports(page);
    await page.mouse.move(center.x, center.y);
    await page.mouse.down();
    await page.mouse.move(center.x, center.y - 70, { steps: 5 });
    await page.mouse.up();
    await waitReport(page, "dragged", draggedAt);
    await page.waitForFunction(() => window.__petShadow.querySelector(".sprite").getAnimations()
        .every(animation => animation.playState !== "running"));
    const dragged = await spriteInfo(page);
    assert.equal(dragged.x, docked.x);
    assert.equal(dragged.width, 8);
    assert.equal(dragged.height, 8);
    assert.ok(Math.abs(dragged.y - docked.y + 70) < 1);
    const saved = await snapshot(page);
    assert.equal(saved.mode, "collapsed");
    assert.equal(saved.fallback, true);
    const restoredPage = await setup(t, fullContent, { sizeDp: 32, fallbackSizePercent: 25 }, saved);
    await restoredPage.waitForFunction(() => !window.__petShadow.querySelector(".sprite").hidden);
    const restored = await spriteInfo(restoredPage);
    assert.equal(restored.width, 8);
    assert.equal(restored.height, 8);
    assert.equal(restored.x, dragged.x);
    assert.equal(restored.y, dragged.y);
    assert.equal((await snapshot(restoredPage)).fallback, true);
    const after = await countReports(page);
    // The target center is outside the 8px image, but must still recognize dismissal.
    await clickPet(page, 3);
    await waitReport(page, "hidden", after);
    assert.equal((await spriteInfo(page)).hidden, true);
});

// SPDX-License-Identifier: GPL-3.0-or-later
"use strict";

/** App-owned overlay, outside #qa. No template hooks or media-library entries are required. */
globalThis.reviewerPet = (() => {
    const geometry = globalThis.reviewerPetGeometry;
    let config,
        media = [],
        generation = "",
        visitId = 0,
        active = false;
    let state = { mode: "hidden", x: 0.5, y: 0.5, media: -1, edge: "right", fallback: false };
    let host,
        sprite,
        visualMedia,
        target,
        position,
        animation,
        sequence = 0,
        clicks = 0,
        clickTimer,
        pointer;
    let refreshFrame,
        postQueue = Promise.resolve(),
        lastActivity = 0,
        loadedMedia = -1;
    let busyUntil = 0,
        scrollUntil = 0,
        scrollTimer,
        mediaLoad,
        configurationSequence = 0;

    function webBusy() {
        return (
            !!document.activeElement?.matches(
                "input,textarea,select,[contenteditable]:not([contenteditable=false])",
            ) ||
            Array.from(document.body.querySelectorAll("audio,video")).some(
                element => !element.paused && !element.ended,
            )
        );
    }

    function presentationBusy() {
        return (
            !!document.activeElement?.matches(
                "input,textarea,select,[contenteditable]:not([contenteditable=false])",
            ) ||
            Array.from(document.body.querySelectorAll("video")).some(
                element => !element.paused && !element.ended,
            )
        );
    }

    function report(action) {
        const body = JSON.stringify({ generation, visitId, action, state, busy: webBusy() });
        postQueue = postQueue
            .catch(() => {})
            .then(() =>
                fetch("ankidroid/reviewerPetState", {
                    method: "POST",
                    body,
                    headers: { "Content-Type": "application/json" },
                }),
            )
            .catch(() => {});
    }

    function activity() {
        busyUntil = performance.now() + 3000;
        if (performance.now() - lastActivity < 500) return;
        lastActivity = performance.now();
        report("activity");
    }

    function createHost() {
        if (host?.isConnected) return;
        host = document.createElement("div");
        host.id = "ankidroid-reviewer-pet";
        host.className = "tappable";
        host.style.cssText =
            "position:fixed;z-index:2147483646;pointer-events:none;overflow:hidden;contain:layout style paint;direction:ltr;-webkit-user-select:none;user-select:none;-webkit-touch-callout:none;-webkit-tap-highlight-color:transparent;";
        const shadow = host.attachShadow({ mode: "closed" });
        const style = document.createElement("style");
        style.textContent =
            ":host{all:initial}.sprite,button,img,video{-webkit-user-select:none;user-select:none;-webkit-touch-callout:none;-webkit-tap-highlight-color:transparent}.sprite{position:absolute;pointer-events:none}img,video{width:100%;height:100%;object-fit:contain;pointer-events:none;-webkit-user-drag:none}button{position:absolute;margin:0;padding:0;border:0;background:transparent;touch-action:none;pointer-events:auto;cursor:pointer}button:focus-visible{outline:2px solid #448aff;outline-offset:-2px}";
        sprite = document.createElement("div");
        sprite.className = "sprite";
        target = document.createElement("button");
        target.type = "button";
        target.setAttribute(
            "aria-label",
            "Reviewer pet: tap to move, double tap to tuck away, triple tap to dismiss",
        );
        target.addEventListener("pointerdown", onDown);
        target.addEventListener("pointermove", onMove);
        target.addEventListener("pointerup", onUp);
        target.addEventListener("pointercancel", cancelPointer);
        target.addEventListener("lostpointercapture", cancelPointer);
        target.addEventListener("click", e => {
            e.preventDefault();
            e.stopPropagation();
            // Keyboard activation is a single click; touch clicks are handled on pointer release.
            if (e.detail === 0 && !pointer) single();
        });
        for (const element of [host, target]) {
            for (const event of ["selectstart", "contextmenu", "dragstart"]) {
                element.addEventListener(event, e => {
                    e.preventDefault();
                    e.stopPropagation();
                });
            }
        }
        shadow.append(style, sprite, target);
        document.documentElement.appendChild(host);
        document.addEventListener(
            "pointerdown",
            e => {
                if (!e.composedPath().includes(host)) activity();
            },
            true,
        );
        document.addEventListener("keydown", activity, true);
        document.addEventListener(
            "focusin",
            () => {
                activity();
                refresh();
            },
            true,
        );
        document.addEventListener(
            "focusout",
            () => {
                activity();
                refresh();
            },
            true,
        );
        for (const event of ["play", "playing", "pause", "ended", "emptied"]) {
            document.addEventListener(
                event,
                () => {
                    report("occupancy");
                    refresh();
                },
                true,
            );
        }
        window.addEventListener(
            "scroll",
            () => {
                activity();
                scrollUntil = performance.now() + 180;
                clearTimeout(scrollTimer);
                scrollTimer = setTimeout(refresh, 180);
                refresh();
            },
            true,
        );
        window.addEventListener("resize", () => {
            cancelPointer();
            refresh();
        });
        window.visualViewport?.addEventListener("resize", refresh);
        window.visualViewport?.addEventListener("scroll", refresh);
        const qa = document.getElementById("qa");
        if (qa) {
            new MutationObserver(refresh).observe(qa, {
                childList: true,
                subtree: true,
                attributes: true,
                characterData: true,
            });
            new ResizeObserver(refresh).observe(qa);
            qa.addEventListener("load", refresh, true);
        }
    }

    function bounds() {
        const vv = window.visualViewport;
        const rootZoom = Number(getComputedStyle(document.documentElement).zoom) || 1;
        const x = vv?.offsetLeft || 0,
            y = vv?.offsetTop || 0;
        const width = vv?.width || window.innerWidth,
            height = vv?.height || window.innerHeight;
        const previous = bounds.previous,
            oldBounds = host.getBoundingClientRect();
        const changed =
            previous &&
            (previous.offsetX !== x ||
                previous.offsetY !== y ||
                previous.width !== width ||
                previous.height !== height ||
                previous.rootZoom !== rootZoom);
        const oldPosition = position;
        const desiredX = state.x,
            desiredY = state.y;
        const painted = changed && sprite && !sprite.hidden ? sprite.getBoundingClientRect() : null;
        const hit = painted && !target.hidden ? target.getBoundingClientRect() : null;
        // A root zoom change has already scaled the old host by the time resize fires.
        // Undo that uncompensated scale before preserving the previously painted coordinates.
        const oldScaleX = oldBounds.width / (parseFloat(host.style.width) || oldBounds.width) || 1;
        const oldScaleY =
            oldBounds.height / (parseFloat(host.style.height) || oldBounds.height) || 1;
        const frozen = rect => ({
            x: previous.x + ((rect.x - oldBounds.x) * previous.scaleX) / oldScaleX,
            y: previous.y + ((rect.y - oldBounds.y) * previous.scaleY) / oldScaleY,
            width: (rect.width * previous.scaleX) / oldScaleX,
            height: (rect.height * previous.scaleY) / oldScaleY,
        });
        const current = painted && painted.width > 0 && painted.height > 0 ? frozen(painted) : null;
        const currentHit = hit ? frozen(hit) : null;
        if (current && animation) {
            cancelAnimation();
            if (state.mode === "moving") state.mode = state.returnMode || "resident";
            if (state.mode === "exiting") state.mode = "hidden";
            // A cancelled flight keeps its return mode until remeasure resumes its remaining path.
            target.hidden = true;
        }
        host.style.zoom = String(1 / rootZoom);
        Object.assign(host.style, {
            left: `${x}px`,
            top: `${y}px`,
            width: `${width}px`,
            height: `${height}px`,
        });
        const r = host.getBoundingClientRect();
        const scaleX = r.width / width || 1,
            scaleY = r.height / height || 1;
        bounds.previous = {
            x: r.x,
            y: r.y,
            scaleX,
            scaleY,
            offsetX: x,
            offsetY: y,
            width,
            height,
            rootZoom,
        };
        if (current) {
            Object.assign(sprite.style, {
                left: `${(current.x - r.x) / scaleX}px`,
                top: `${(current.y - r.y) / scaleY}px`,
                width: `${current.width / scaleX}px`,
                height: `${current.height / scaleY}px`,
            });
            position = { ...oldPosition, ...current };
            if (currentHit) position.hit = currentHit;
            else if (oldPosition?.hit)
                position.hit = {
                    ...oldPosition.hit,
                    x: oldPosition.hit.x + current.x - oldPosition.x,
                    y: oldPosition.hit.y + current.y - oldPosition.y,
                };
            const targetRect = position.hit || current;
            Object.assign(target.style, {
                left: `${(targetRect.x - r.x) / scaleX}px`,
                top: `${(targetRect.y - r.y) / scaleY}px`,
                width: `${targetRect.width / scaleX}px`,
                height: `${targetRect.height / scaleY}px`,
            });
            // Keep the saved relative destination; position is only the live start of the resize move.
            state.x = desiredX;
            state.y = desiredY;
        }
        return { x: r.left, y: r.top, width: r.width, height: r.height };
    }

    function measurements() {
        const b = bounds();
        const qa = document.getElementById("qa");
        return {
            bounds: b,
            ...(qa ? geometry.collect(document.body, b) : { content: [], controls: [] }),
        };
    }

    function usable() {
        return (
            config?.enabled && media.length > 0 && active && !document.hidden && !presentationBusy()
        );
    }

    function hide() {
        if (sprite) sprite.hidden = target.hidden = true;
    }

    function savePosition(p) {
        position = p;
        const b = bounds();
        state.x = Math.max(0, Math.min(1, (p.x - b.x) / Math.max(1, b.width - p.width)));
        state.y = Math.max(0, Math.min(1, (p.y - b.y) / Math.max(1, b.height - p.height)));
        if ("fallback" in p) state.fallback = !!p.fallback;
        if (p.edge) state.edge = p.edge;
    }

    function draw(p, interactive = true) {
        if (!usable() || !p) {
            hide();
            return;
        }
        const b = bounds();
        Object.assign(sprite.style, {
            left: `${p.x - b.x}px`,
            top: `${p.y - b.y}px`,
            width: `${p.width}px`,
            height: `${p.height}px`,
            opacity: String(config.opacityPercent / 100),
        });
        const hit = p.hit || p;
        Object.assign(target.style, {
            left: `${hit.x - b.x}px`,
            top: `${hit.y - b.y}px`,
            width: `${hit.width}px`,
            height: `${hit.height}px`,
        });
        sprite.hidden = false;
        target.hidden = !interactive;
    }

    function cancelAnimation() {
        sequence++;
        if (!animation) return;
        const r = sprite.getBoundingClientRect(),
            b = host.getBoundingClientRect();
        animation.cancel();
        animation = null;
        // Cancelling a Web Animation reveals inline styles, which previously contained its destination.
        // Commit the actually painted rectangle first so interruption never exposes that destination.
        Object.assign(sprite.style, {
            left: `${r.x - b.x}px`,
            top: `${r.y - b.y}px`,
            width: `${r.width}px`,
            height: `${r.height}px`,
        });
        const old = position;
        position = { ...old, x: r.x, y: r.y, width: r.width, height: r.height };
        if (old?.hit)
            position.hit = { ...old.hit, x: old.hit.x + r.x - old.x, y: old.hit.y + r.y - old.y };
        state.x = Math.max(0, Math.min(1, (r.x - b.x) / Math.max(1, b.width - r.width)));
        state.y = Math.max(0, Math.min(1, (r.y - b.y) / Math.max(1, b.height - r.height)));
    }

    function cancelClicks() {
        clearTimeout(clickTimer);
        clicks = 0;
    }

    function motionEasing() {
        // Even the legacy linear/ease-out presets now start and finish at rest.
        return config.movementCurve === "ease-in-out"
            ? "ease-in-out"
            : config.movementCurve === "linear"
              ? "cubic-bezier(0.45, 0, 0.55, 1)"
              : "cubic-bezier(0.4, 0, 0.2, 1)";
    }

    function motionProgress(t) {
        if (t === 0 || t === 1) return t;
        if (config.movementCurve === "minimum-jerk") return t ** 3 * (10 + t * (-15 + 6 * t));
        // Integral of v(t) = t(1-t)^3(3220 + 43275t + 20125t^2)/1026.
        // Unit distance/duration: peak speed 2 at t=0.4; final acceleration and jerk are zero.
        return (
            (t ** 2 * (1610 + t * (11205 + t * (-25010 + t * (13246 + t * (2850 - 2875 * t)))))) /
            1026
        );
    }

    function animateMotion(frames, duration) {
        const sampled = ["minimum-jerk", "asymmetric-smooth-stop"].includes(config.movementCurve);
        // Sample displacement, not velocity. Ordinary linear keyframes also work in older WebViews.
        const steps = sampled ? 200 : 1;
        const easing = sampled ? "linear" : motionEasing();
        const keyframes = [];
        const frameAt = (from, to, progress, offset) => {
            const frame = { offset, easing };
            for (const name of Object.keys(from)) {
                if (name === "offset") continue;
                const value = from[name] + (to[name] - from[name]) * progress;
                if (name === "translateY") frame.transform = `translateY(${value}px)`;
                else frame[name] = name === "opacity" ? value : `${value}px`;
            }
            return frame;
        };
        for (let segment = 0; segment < frames.length - 1; segment++) {
            const from = frames[segment],
                to = frames[segment + 1];
            const start = from.offset ?? segment / (frames.length - 1),
                end = to.offset ?? (segment + 1) / (frames.length - 1);
            for (let step = 0; step < steps; step++) {
                const t = step / steps;
                keyframes.push(
                    frameAt(from, to, sampled ? motionProgress(t) : t, start + (end - start) * t),
                );
            }
        }
        const last = frames[frames.length - 1];
        keyframes.push(frameAt(last, last, 0, last.offset ?? 1));
        return sprite.animate(keyframes, { duration, easing: "linear" });
    }

    function selectMedia() {
        if (!media.length) return false;
        if (state.media < 0 || state.media >= media.length)
            state.media = Math.floor(Math.random() * media.length);
        const url = media[state.media].url;
        if (
            loadedMedia === url &&
            visualMedia &&
            (visualMedia.tagName === "VIDEO"
                ? visualMedia.readyState >= 2
                : visualMedia.complete && visualMedia.naturalWidth > 0)
        )
            return true;
        if (mediaLoad?.url === url) return mediaLoad.promise;
        const video = media[state.media].mimeType?.startsWith("video/");
        const element = document.createElement(video ? "video" : "img");
        element.draggable = false;
        if (video) {
            element.muted = true;
            element.loop = true;
            element.autoplay = true;
            element.playsInline = true;
        } else element.alt = "";
        element.addEventListener("error", () => {
            if (visualMedia !== element) return;
            cancelAnimation();
            state.mode = "hidden";
            hide();
            report("mediaFailed");
        });
        const request = { url };
        mediaLoad = request;
        request.promise = (async () => {
            try {
                if (video) {
                    const ready = new Promise((resolve, reject) => {
                        const timeout = setTimeout(
                            () => reject(new Error("Media load timed out")),
                            10000,
                        );
                        element.addEventListener(
                            "loadeddata",
                            () => {
                                clearTimeout(timeout);
                                resolve();
                            },
                            { once: true },
                        );
                        element.addEventListener(
                            "error",
                            () => {
                                clearTimeout(timeout);
                                reject(new Error("Media load failed"));
                            },
                            { once: true },
                        );
                    });
                    element.src = url;
                    await ready;
                } else {
                    element.src = url;
                    await element.decode();
                }
                if (
                    mediaLoad !== request ||
                    !config.enabled ||
                    media[state.media]?.url !== url ||
                    !(video ? element.videoWidth > 0 : element.naturalWidth > 0)
                )
                    return false;
                visualMedia?.pause?.();
                visualMedia = element;
                loadedMedia = url;
                sprite.replaceChildren(element);
                if (video && active) element.play().catch(() => {});
                return true;
            } catch {
                return false;
            } finally {
                if (mediaLoad === request) mediaLoad = null;
            }
        })();
        return request.promise;
    }

    function landing(alternate = false, automatic = false) {
        const m = measurements();
        const old = position || {
            x: m.bounds.x + state.x * (m.bounds.width - config.sizeDp),
            y: m.bounds.y + state.y * (m.bounds.height - config.sizeDp),
            width: config.sizeDp,
            height: config.sizeDp,
        };
        if (
            automatic &&
            position &&
            geometry.safe(position.hit || position, m.bounds, m.controls, config.avoidanceDp)
        ) {
            // Scrolling moves text under a fixed overlay. Keep a modest overlap instead of chasing every line.
            const overlap = m.content.reduce(
                (area, r) =>
                    area +
                    Math.max(0, Math.min(old.x + old.width, r.x + r.width) - Math.max(old.x, r.x)) *
                        Math.max(
                            0,
                            Math.min(old.y + old.height, r.y + r.height) - Math.max(old.y, r.y),
                        ),
                0,
            );
            if (
                performance.now() < scrollUntil ||
                (!old.fallback && overlap <= old.width * old.height * 0.25)
            )
                return old;
        }
        return geometry.place(
            m.bounds,
            m.content,
            m.controls,
            config.sizeDp,
            config.fallbackSizePercent,
            config.avoidanceDp,
            old,
            alternate,
        );
    }

    function docked(preference = state.edge) {
        const m = measurements();
        const size = state.fallback
            ? (config.sizeDp * config.fallbackSizePercent) / 100
            : config.sizeDp;
        const y = m.bounds.y + state.y * Math.max(0, m.bounds.height - size);
        return geometry.dock(
            m.bounds,
            m.controls,
            size,
            config.edgeHidePercent,
            config.avoidanceDp,
            y,
            preference,
        );
    }

    async function moveTo(p, mode, action, passive = false, duration = config.moveDurationMs) {
        if (!usable()) {
            hide();
            report("unavailable");
            return;
        }
        if (!p) {
            target.hidden = true;
            report("unavailable");
            return;
        }
        const b = bounds();
        const drawn = position && !sprite.hidden ? sprite.getBoundingClientRect() : null;
        const from = drawn
            ? { x: drawn.x, y: drawn.y, width: drawn.width, height: drawn.height }
            : position || { ...p, x: p.x < b.x + b.width / 2 ? b.x - p.width : b.x + b.width };
        cancelAnimation();
        const token = sequence;
        state.returnMode = mode;
        if (!passive) state.mode = "moving";
        savePosition(p);
        draw(p, !!pointer);
        report(passive ? "geometry" : "moving");
        animation = animateMotion(
            [
                {
                    left: from.x - b.x,
                    top: from.y - b.y,
                    width: from.width,
                    height: from.height,
                },
                {
                    left: p.x - b.x,
                    top: p.y - b.y,
                    width: p.width,
                    height: p.height,
                },
            ],
            duration,
        );
        try {
            await animation.finished;
        } catch {
            return;
        }
        if (token !== sequence || !usable()) return;
        animation = null;
        state.mode = mode;
        state.returnMode = mode;
        draw(p);
        report(action);
        remeasure();
    }

    async function fly() {
        if (!usable() || webBusy() || pointer || clicks || animation) {
            report("skipped");
            return;
        }
        const original = state.mode;
        if (!["hidden", "resident", "collapsed"].includes(original)) {
            report("skipped");
            return;
        }
        cancelAnimation();
        const request = sequence,
            requestedVisit = visitId;
        if (original === "hidden") state.media = -1;
        if (!(await selectMedia()) || !usable()) {
            report("unavailable");
            return;
        }
        if (webBusy()) {
            report("skipped");
            return;
        }
        if (request !== sequence || requestedVisit !== visitId) {
            report("skipped");
            return;
        }
        const b = bounds(),
            size = Math.min(config.sizeDp, b.width, b.height);
        if (size <= 0) {
            report("unavailable");
            return;
        }
        // The flight is decorative and cannot intercept input; it may pass over card controls.
        const y = b.y + Math.max(0, b.height - size) * (position ? state.y : 0.35);
        const landingPosition =
            original === "collapsed"
                ? docked()
                : original === "resident"
                  ? landing(false, true)
                  : null;
        const current =
            original !== "hidden" && position && !sprite.hidden
                ? sprite.getBoundingClientRect()
                : null;
        const from = current
            ? { x: current.x, y: current.y, width: current.width, height: current.height }
            : original !== "hidden" && position
              ? position
              : { x: b.x - size, y, width: size, height: size };
        const end =
            original === "hidden"
                ? { ...from, x: b.x + b.width }
                : landingPosition || { ...position };
        cancelAnimation();
        const token = sequence;
        state.returnMode = original;
        state.mode = "flying";
        draw(end, false);
        report("flightStarted");
        const frame = (p, offset) => ({
            left: p.x - b.x,
            top: p.y - b.y,
            width: p.width,
            height: p.height,
            offset,
        });
        const frames = [frame(from, 0)];
        if (original !== "hidden")
            frames.push(frame({ x: b.x + b.width, y, width: size, height: size }, 0.6));
        frames.push(frame(end, 1));
        animation = animateMotion(frames, config.moveDurationMs * 2);
        try {
            await animation.finished;
        } catch {
            return;
        }
        if (token !== sequence) return;
        animation = null;
        state.mode = original;
        if (original === "hidden") hide();
        else {
            savePosition(end);
            draw(end, !!landingPosition);
            remeasure();
        }
        report("flightEnded");
    }

    async function appear() {
        if (
            !usable() ||
            webBusy() ||
            state.mode !== "hidden" ||
            pointer ||
            clicks ||
            performance.now() < busyUntil
        ) {
            report("skipped");
            return;
        }
        cancelAnimation();
        const request = sequence,
            requestedVisit = visitId;
        state.media = -1;
        if (!(await selectMedia()) || !usable()) {
            report("unavailable");
            return;
        }
        if (webBusy()) {
            report("skipped");
            return;
        }
        if (request !== sequence || requestedVisit !== visitId) {
            report("skipped");
            return;
        }
        position = null;
        await moveTo(landing(), "resident", "appeared");
    }

    function single() {
        if (!usable() || animation) return;
        moveTo(landing(state.mode !== "collapsed"), "resident", "moved");
    }

    function double() {
        if (state.mode === "collapsed") {
            report("held");
            return;
        }
        const b = bounds();
        const edge =
            config.edgePreference === "nearest"
                ? (position?.x || 0) + (position?.width || 0) / 2 < b.x + b.width / 2
                    ? "left"
                    : "right"
                : config.edgePreference;
        const p = docked(edge);
        if (p) moveTo(p, "collapsed", "collapsed");
    }

    async function dismiss() {
        cancelClicks();
        cancelAnimation();
        const token = sequence;
        state.mode = "exiting";
        state.returnMode = "hidden";
        target.hidden = true;
        report("dismissed");
        animation = animateMotion(
            [
                { opacity: config.opacityPercent / 100, translateY: 0 },
                { opacity: 0, translateY: -30 },
            ],
            Math.min(600, config.moveDurationMs),
        );
        try {
            await animation.finished;
        } catch {
            return;
        }
        if (token !== sequence) return;
        animation = null;
        state.mode = "hidden";
        position = null;
        hide();
        report("hidden");
    }

    function onDown(e) {
        e.preventDefault();
        e.stopPropagation();
        if (!e.isPrimary || pointer || !usable()) return;
        clearTimeout(clickTimer);
        pointer = {
            id: e.pointerId,
            x: e.clientX,
            y: e.clientY,
            time: performance.now(),
            p: position,
            dragged: false,
        };
        target.setPointerCapture(e.pointerId);
    }

    function onMove(e) {
        e.preventDefault();
        e.stopPropagation();
        if (!pointer || pointer.id !== e.pointerId || state.mode !== "collapsed") return;
        const delta = ((e.clientY - pointer.y) * config.dragSensitivityPercent) / 100;
        if (!pointer.dragged && Math.abs(delta) < 8) return;
        pointer.dragged = true;
        cancelClicks();
        const m = measurements(),
            p = pointer.p;
        if (!p) return;
        const y = Math.max(
            m.bounds.y,
            Math.min(m.bounds.y + m.bounds.height - p.height, p.y + delta),
        );
        const candidate = {
            ...p,
            y,
            hit: {
                ...p.hit,
                y: Math.max(
                    m.bounds.y,
                    Math.min(
                        m.bounds.y + m.bounds.height - p.hit.height,
                        y + (p.height - p.hit.height) / 2,
                    ),
                ),
            },
        };
        if (geometry.safe(candidate.hit, m.bounds, m.controls, config.avoidanceDp)) {
            moveTo(candidate, "collapsed", "geometry", true, 150);
        }
    }

    function onUp(e) {
        e.preventDefault();
        e.stopPropagation();
        if (!pointer || pointer.id !== e.pointerId) return;
        const held =
            pointer.dragged ||
            performance.now() - pointer.time >= 400 ||
            Math.hypot(e.clientX - pointer.x, e.clientY - pointer.y) >= 8;
        pointer = null;
        if (target.hasPointerCapture(e.pointerId)) target.releasePointerCapture(e.pointerId);
        if (held) {
            cancelClicks();
            report("dragged");
            return;
        }
        clicks++;
        if (clicks >= 3) {
            dismiss();
            return;
        }
        clickTimer = setTimeout(() => {
            const count = clicks;
            clicks = 0;
            count === 1 ? single() : double();
        }, config.multiClickMs);
    }

    function cancelPointer() {
        if (!pointer) return;
        const id = pointer.id;
        pointer = null;
        cancelClicks();
        if (target.hasPointerCapture(id)) target.releasePointerCapture(id);
        report("dragged");
    }

    function remeasure() {
        if (!host || !config) return;
        bounds();
        if (!usable() || state.mode === "hidden" || state.mode === "exiting") {
            hide();
            return;
        }
        if (animation || pointer) return;
        if (state.mode === "flying") {
            const mode = state.returnMode || "hidden",
                b = bounds();
            const p =
                mode === "hidden"
                    ? { ...position, x: b.x + b.width }
                    : (mode === "collapsed" ? docked() : landing(false, true)) || position;
            moveTo(p, mode, "flightEnded");
            return;
        }
        const p = state.mode === "collapsed" ? docked() : landing(false, true);
        // A transient layout without a landing spot must not blink an already visible pet.
        // Disable its hit target so controls stay usable underneath while geometry settles.
        if (!p) {
            target.hidden = true;
            return;
        }
        if (
            position &&
            (Math.abs(position.x - p.x) > 0.5 ||
                Math.abs(position.y - p.y) > 0.5 ||
                Math.abs(position.width - p.width) > 0.5 ||
                Math.abs(position.height - p.height) > 0.5)
        ) {
            moveTo(p, state.mode === "collapsed" ? "collapsed" : "resident", "geometry", true);
            return;
        }
        savePosition(p);
        draw(p);
        report("geometry");
    }

    function refresh() {
        // Keep the current sprite visible while coalescing layout/scroll events once per frame.
        // Hiding here also made a running flight invisible: remeasure skips active animations.
        if (!usable()) hide();
        if (refreshFrame !== undefined) return;
        refreshFrame = requestAnimationFrame(() => {
            refreshFrame = undefined;
            remeasure();
        });
    }

    async function configure(payload) {
        const token = ++configurationSequence;
        const fresh = generation !== payload.generation;
        const oldUrl = media[state.media]?.url;
        config = payload.config;
        media = payload.media;
        visitId = payload.visitId;
        if (!fresh && oldUrl) {
            const index = media.findIndex(item => item.url === oldUrl);
            state.media = index;
            if (index < 0) {
                cancelAnimation();
                state.mode = state.returnMode || state.mode;
                loadedMedia = -1;
                mediaLoad = null;
            }
        }
        if (fresh) {
            cancelClicks();
            cancelPointer();
            cancelAnimation();
            generation = payload.generation;
            state = { ...state, ...payload.state };
            if (["flying", "moving", "exiting"].includes(state.mode))
                state.mode = state.returnMode || "hidden";
            loadedMedia = -1;
            mediaLoad = null;
            if (sprite?.hidden || !sprite?.isConnected) position = null;
        }
        if (!config.enabled || !media.length) {
            visualMedia?.pause?.();
            cancelClicks();
            cancelPointer();
            cancelAnimation();
            state.mode = "hidden";
            hide();
            report("disabled");
            return;
        }
        createHost();
        const ready = state.mode !== "hidden" && (await selectMedia());
        if (token !== configurationSequence) return;
        if (ready) remeasure();
        else hide();
        if (typeof _queueAction === "function") _queueAction(() => report("rendered"));
        else report("rendered");
    }

    function setActive(value) {
        if (active === value) return;
        active = value;
        if (!active) {
            visualMedia?.pause?.();
            cancelPointer();
            cancelClicks();
            const wasAnimating = !!animation;
            cancelAnimation();
            if (wasAnimating) state.mode = state.returnMode || "hidden";
            hide();
            report("paused");
        } else {
            visualMedia?.play?.().catch(() => {});
            busyUntil = performance.now() + 3000;
            refresh();
        }
    }

    function command(action, expectedVisit) {
        if (expectedVisit !== visitId) {
            report("skipped");
            return;
        }
        if (action === "fly") fly();
        if (action === "appear") appear();
        if (action === "expand") {
            if (
                usable() &&
                !webBusy() &&
                !pointer &&
                !clicks &&
                !animation &&
                performance.now() >= busyUntil
            )
                single();
            else report("skipped");
        }
    }

    // The backend rendering entry points enqueue internally. Hooks are queued after them,
    // rather than awaiting their undefined return values or replacing backend assets.
    for (const name of ["_showQuestion", "_showAnswer"]) {
        const original = globalThis[name];
        if (typeof original !== "function") continue;
        globalThis[name] = function (...args) {
            const result = original.apply(this, args);
            if (typeof _queueAction === "function")
                _queueAction(() => {
                    remeasure();
                    report("rendered");
                });
            else {
                refresh();
                report("rendered");
            }
            return result;
        };
    }

    return {
        configure,
        setActive,
        command,
        visit(value) {
            visitId = value;
            refresh();
        },
        snapshot() {
            report("snapshot");
        },
    };
})();

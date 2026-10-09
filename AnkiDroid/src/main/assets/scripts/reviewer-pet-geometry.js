// SPDX-License-Identifier: GPL-3.0-or-later
"use strict";

/** All rectangles use viewport CSS coordinates; controls are mandatory exclusions. */
globalThis.reviewerPetGeometry = (() => {
    const intersects = (a, b, gap = 0) =>
        a.x < b.x + b.width + gap &&
        a.x + a.width + gap > b.x &&
        a.y < b.y + b.height + gap &&
        a.y + a.height + gap > b.y;
    const inside = (r, bounds) =>
        r.x >= bounds.x &&
        r.y >= bounds.y &&
        r.x + r.width <= bounds.x + bounds.width &&
        r.y + r.height <= bounds.y + bounds.height;
    const safe = (r, bounds, obstacles, gap) =>
        inside(r, bounds) && !obstacles.some(o => intersects(r, o, gap));

    function place(
        bounds,
        content,
        controls,
        size,
        fallbackPercent,
        gap,
        previous,
        alternate = false,
    ) {
        const obstacles = content.concat(controls);
        if (
            !alternate &&
            previous &&
            previous.width === size &&
            safe(previous, bounds, obstacles, gap)
        )
            return previous;
        const candidates = [];
        const step = Math.max(12, size / 2);
        // Include the last row and column: a narrow right/bottom strip must not be missed.
        const xs = [bounds.x + gap, bounds.x + bounds.width - size - gap];
        const ys = [bounds.y + gap, bounds.y + bounds.height - size - gap];
        for (let x = xs[0]; x <= xs[1]; x += step) xs.push(x);
        for (let y = ys[0]; y <= ys[1]; y += step) ys.push(y);
        for (const y of ys)
            for (const x of xs) {
                const r = { x, y, width: size, height: size, fallback: false };
                if (
                    safe(r, bounds, obstacles, gap) &&
                    (!alternate ||
                        !previous ||
                        Math.hypot(x - previous.x, y - previous.y) >= size / 2)
                )
                    candidates.push(r);
            }
        if (candidates.length) {
            // Layout changes should keep the pet nearby; intentional single-click moves stay random.
            if (!alternate && previous) {
                const distance = r => Math.hypot(r.x - previous.x, r.y - previous.y);
                return candidates.reduce((nearest, candidate) =>
                    distance(candidate) < distance(nearest) ? candidate : nearest,
                );
            }
            return candidates[Math.floor(Math.random() * candidates.length)];
        }
        const small = (size * fallbackPercent) / 100;
        const fallback = {
            x: bounds.x + bounds.width - small - gap,
            y: bounds.y + bounds.height - small - gap,
            width: small,
            height: small,
            fallback: true,
        };
        if (small < 24)
            fallback.hit = {
                x: bounds.x + bounds.width - 24 - gap,
                y: bounds.y + bounds.height - 24 - gap,
                width: 24,
                height: 24,
            };
        // Ordinary content may be covered only in this explicitly smaller corner fallback.
        return safe(fallback.hit || fallback, bounds, controls, gap) ? fallback : null;
    }

    function dock(bounds, controls, size, hidePercent, gap, desiredY, preferredEdge) {
        const visible = size * (1 - hidePercent / 100);
        const hitWidth = Math.max(24, visible);
        const hitHeight = Math.max(24, size);
        if (hitHeight > bounds.height || hitWidth > bounds.width) return null;
        const edges = preferredEdge === "left" ? ["left", "right"] : ["right", "left"];
        const maxY = bounds.y + bounds.height - size;
        const ys = [Math.max(bounds.y, Math.min(maxY, desiredY)), bounds.y, maxY];
        for (let y = bounds.y; y <= maxY; y += 8) ys.push(y);
        ys.sort((a, b) => Math.abs(a - desiredY) - Math.abs(b - desiredY));
        for (const edge of edges)
            for (const y of ys) {
                const hit = {
                    x: edge === "left" ? bounds.x : bounds.x + bounds.width - hitWidth,
                    y: Math.max(
                        bounds.y,
                        Math.min(bounds.y + bounds.height - hitHeight, y + (size - hitHeight) / 2),
                    ),
                    width: hitWidth,
                    height: hitHeight,
                };
                if (!safe(hit, bounds, controls, gap)) continue;
                return {
                    x:
                        edge === "left"
                            ? bounds.x - (size * hidePercent) / 100
                            : bounds.x + bounds.width - visible,
                    y,
                    width: size,
                    height: size,
                    edge,
                    hit,
                };
            }
        return null;
    }

    function collect(root, bounds) {
        const content = [],
            controls = [];
        const controlSelector =
            "input,textarea,select,button,a[href],label,video,audio,iframe,[contenteditable]:not([contenteditable=false]),[role=button],[role=link],[role=slider],[tabindex],[onclick],[onpointerdown],[ontouchstart],.tappable,.replay-button,.replaybutton,.soundLink";
        const visible = el => {
            const style = getComputedStyle(el);
            return (
                style.display !== "none" &&
                style.visibility !== "hidden" &&
                Number(style.opacity) !== 0
            );
        };
        const append = (rects, target) => {
            for (const r of rects) {
                const rect = { x: r.left, y: r.top, width: r.width, height: r.height };
                if (r.width > 0 && r.height > 0 && intersects(rect, bounds)) target.push(rect);
            }
        };
        for (const el of root.querySelectorAll(controlSelector))
            if (visible(el)) append(el.getClientRects(), controls);
        for (const el of root.querySelectorAll(
            "img,svg,canvas,object,embed,hr,ruby,math,mjx-container,.MathJax,.MathJax_Display",
        ))
            if (visible(el)) append(el.getClientRects(), content);
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
        let text;
        while ((text = walker.nextNode())) {
            const parent = text.parentElement;
            if (
                !text.textContent.trim() ||
                !parent ||
                parent.closest("script,style,noscript") ||
                !visible(parent)
            )
                continue;
            const range = document.createRange();
            range.selectNodeContents(text);
            append(range.getClientRects(), content);
            range.detach();
        }
        return { content, controls };
    }
    return { intersects, inside, safe, place, dock, collect };
})();

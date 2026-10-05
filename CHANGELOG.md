# Changelog

## 0.1.1

* **Studies preview.** A table titled "Studies (in analysis order)" now sits under the *Open file* / *Example data*
  buttons and follows the "Order studies by" choice, so the data and the order used by the analysis can be checked
  before running. The Settings section is unchanged (the data/settings boundary is a draggable divider).
* **1200 dpi chart export** (13,200 x 9,000 px), in addition to 150, 300 and 600 dpi. The PNG is now streamed to disk
  band by band, so even 1200 dpi needs only a small amount of memory, and the resolution is stored in the file.
  Also available on the command line (`--dpi 1200`).
* **No more horizontal scrolling** in the Summary (abbreviations), Full log and Warnings tabs: the text wraps to the
  window width.
* Numbers read from .xlsx are rounded to Excel's 15 significant digits, so a stored 0.5340000000000001 shows as 0.534.
* Table column headers are always shown.

## 0.1.0

First release: Java port of the R package tsacor 0.1.0 (graphical application and command line).
DARIS labels in the lower part of the chart when the Z-curve is positive, upper part when negative.

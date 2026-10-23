package org.koitharu.kotatsu.japanese.ocr

/**
 * Puts the bubbles on a page into the order a Japanese reader takes them.
 *
 * A manga page is read in tiers: the top row of panels right to left, then the next row down. The
 * blocks are therefore grouped into rows - a block joins the current row when it overlaps it
 * vertically by at least half of its own height - and each row is read from its rightmost block.
 * Panel borders are not detected, so an odd layout can still come out in the wrong order; it is the
 * same text either way, only listed differently.
 */
object MangaReadingOrder {

	fun sort(blocks: List<OcrBlock>): List<OcrBlock> = sortBy(
		items = blocks,
		top = { it.bounds.top },
		bottom = { it.bounds.bottom },
		right = { it.bounds.right },
	)

	// Plain ints rather than a Rect so this can be unit tested without an Android runtime.
	internal fun <T> sortBy(
		items: List<T>,
		top: (T) -> Int,
		bottom: (T) -> Int,
		right: (T) -> Int,
	): List<T> {
		if (items.size < 2) {
			return items
		}
		val rows = ArrayList<Row<T>>()
		for (item in items.sortedBy(top)) {
			val row = rows.lastOrNull()
			val height = bottom(item) - top(item)
			if (row != null && row.overlapOf(top(item), bottom(item)) * 2 >= height) {
				row.add(item, top(item), bottom(item))
			} else {
				rows += Row<T>().also { it.add(item, top(item), bottom(item)) }
			}
		}
		return rows.flatMap { row -> row.items.sortedByDescending(right) }
	}

	private class Row<T> {

		val items = ArrayList<T>()
		private var top = Int.MAX_VALUE
		private var bottom = Int.MIN_VALUE

		fun add(item: T, itemTop: Int, itemBottom: Int) {
			items += item
			top = minOf(top, itemTop)
			bottom = maxOf(bottom, itemBottom)
		}

		fun overlapOf(itemTop: Int, itemBottom: Int): Int =
			(minOf(bottom, itemBottom) - maxOf(top, itemTop)).coerceAtLeast(0)
	}
}

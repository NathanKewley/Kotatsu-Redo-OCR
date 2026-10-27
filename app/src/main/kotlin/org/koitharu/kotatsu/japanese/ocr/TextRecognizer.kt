package org.koitharu.kotatsu.japanese.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * Reads the Japanese text off a manga page, one [OcrBlock] per speech bubble (or caption), in the
 * order a Japanese reader would take them: right to left, top to bottom.
 */
interface TextRecognizer {

	/** Reads [bitmap], which stays the caller's: it is not recycled here. */
	suspend fun recognize(bitmap: Bitmap): List<OcrBlock>
}

/** One block of text, with [bounds] and [charSize] in the pixels of the page that was read. */
data class OcrBlock(
	val text: String,
	val bounds: Rect,
	val isVertical: Boolean,
	/** The size of one character: what tells a paragraph's next line from the next paragraph. */
	val charSize: Int,
)

fun Char.isJapanese(): Boolean = this in '぀'..'ヿ' || // hiragana, katakana
	this in '一'..'鿿' || // CJK unified ideographs
	this in '㐀'..'䶿' || // CJK extension A
	this == '々' // 々

/** What every recogniser does with the blocks it found: paragraphs joined, noise dropped, put in order. */
internal fun arrange(blocks: List<OcrBlock>): List<OcrBlock> {
	val bubbles = Paragraphs.join(blocks).filter { block ->
		// Page numbers, scanlator credits and lone sound effects. A sidebar full of "!?" and "ド"
		// is noise between the lines worth studying.
		block.text.count { it.isJapanese() } >= MIN_JAPANESE_CHARS
	}
	return MangaReadingOrder.sort(bubbles)
}

/**
 * Splits a webtoon strip into screen-shaped slices.
 *
 * Fed whole, a 800x12000 strip is scaled down by the recogniser until the text is a few pixels
 * high and nothing is read at all. A bubble cut in two by a slice boundary comes back as two
 * blocks, which is a far smaller loss.
 */
internal fun tiles(width: Int, height: Int): List<Rect> {
	val tileHeight = max(width * 2, MIN_TILE_HEIGHT)
	if (height <= tileHeight * 5 / 4) {
		return listOf(Rect(0, 0, width, height))
	}
	return (0 until height step tileHeight).map { top ->
		Rect(0, top, width, min(top + tileHeight, height))
	}
}

private const val MIN_TILE_HEIGHT = 1200
private const val MIN_JAPANESE_CHARS = 2

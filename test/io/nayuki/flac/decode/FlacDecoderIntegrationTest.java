/*
 * FLAC library (Java)
 *
 * Copyright (c) Project Nayuki
 * https://www.nayuki.io/page/flac-library-java
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program (see COPYING.txt and COPYING.LESSER.txt).
 * If not, see <http://www.gnu.org/licenses/>.
 */

package io.nayuki.flac.decode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static io.nayuki.flac.decode.TestUtil.*;


/**
 * High-level integration test for {@link FlacDecoder}, which is constructed only over a
 * {@link java.io.File} and therefore always uses {@link SeekableFileFlacInput}. It verifies
 * that the unified seek contract still supports the decoder's full seek machinery, including
 * the sync-scanning binary-search path ({@code seekBySyncAndDecode}) that performs many
 * interleaved seeks and byte reads near and at the end of the stream.
 */
final class FlacDecoderIntegrationTest {

	static void run() throws IOException {
		System.out.println("== FlacDecoder high-level seek integration ==");
		int before = passedChecks;
		FlacTestData.FlacFile file = FlacTestData.makeLarge();
		Path path = Files.createTempFile("flac-input-contract-", ".flac");
		try {
			Files.write(path, file.bytes);
			int numSamples = file.samples[0].length;

			try (FlacDecoder dec = new FlacDecoder(path.toFile())) {
				while (dec.readAndHandleMetadataBlock() != null)
					;
				int channels = file.samples.length;

				// 1. Stream the first blocks contiguously
				int[][] block = new int[channels][65536];
				int got = dec.readAudioBlock(block, 0);
				check(got > 0, "first audio block read");
				for (int ch = 0; ch < channels; ch++)
					for (int i = 0; i < got; i++)
						checkEq(block[ch][i], file.samples[ch][i], "contiguous sample " + i);

				// 2. Seek positions, including ones far enough apart to trigger the
				//    >300,000-sample binary-search/sync-scan path, plus edge positions.
				int[] seekPositions = {0, 1, got - 1, 50000, 123457, numSamples / 2,
					numSamples - 4096, numSamples - 2, numSamples - 1};
				for (int pos : seekPositions) {
					int filled = dec.seekAndReadAudioBlock(pos, block, 0);
					// The call fills from 'pos' up to the end of the frame containing 'pos'.
					int frameEnd = (int)((long)(pos / file.blockSize + 1) * file.blockSize);
					int expected = Math.min(frameEnd, numSamples) - pos;
					checkEq(filled, expected, "filled count for seek to sample " + pos);
					for (int ch = 0; ch < channels; ch++)
						for (int i = 0; i < filled; i++)
							checkEq(block[ch][i], file.samples[ch][pos + i],
								"samples after seek pos=" + pos + " i=" + i);
				}

				// 3. After all the seeking, ordinary contiguous streaming still works from a fresh seek
				int mid = numSamples / 3;
				int firstFilled = dec.seekAndReadAudioBlock(mid, block, 0);
				check(firstFilled > 0, "contiguous streaming resumes after seeks");
				for (int ch = 0; ch < channels; ch++)
					for (int i = 0; i < firstFilled; i++)
						checkEq(block[ch][i], file.samples[ch][mid + i],
							"post-seek contiguous sample " + i);
				// And the next block follows on contiguously without another seek
				int nextFilled = dec.readAudioBlock(block, 0);
				check(nextFilled > 0, "next block read contiguously after seek");
				int contPos = mid + firstFilled;
				for (int ch = 0; ch < channels; ch++)
					for (int i = 0; i < nextFilled; i++)
						checkEq(block[ch][i], file.samples[ch][contPos + i],
							"contiguous next-block sample " + i);

				// 4. Reading at the very end yields zero samples without error
				int endRead = dec.seekAndReadAudioBlock(numSamples, new int[channels][65536], 0);
				checkEq(endRead, 0, "seek beyond last sample yields zero samples");
			}
		} finally {
			Files.deleteIfExists(path);
		}
		System.out.println("   passed " + (passedChecks - before) + " checks");
	}


	private FlacDecoderIntegrationTest() {}

}

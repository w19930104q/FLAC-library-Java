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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import io.nayuki.flac.common.StreamInfo;
import io.nayuki.flac.encode.BitOutputStream;
import io.nayuki.flac.encode.FlacEncoder;
import io.nayuki.flac.encode.SubframeEncoder;


/**
 * Produces real FLAC byte streams entirely in memory using the library's own encoder,
 * so the decode/seek tests exercise genuine frame headers, Rice residuals, and CRC
 * footers instead of synthetic bit patterns.
 */
final class FlacTestData {

	// A complete FLAC file plus the samples that were encoded (the expected decode output).
	static final class FlacFile {
		final byte[] bytes;
		final int[][] samples;
		final StreamInfo streamInfo;
		final int blockSize;

		FlacFile(byte[] bytes, int[][] samples, StreamInfo streamInfo, int blockSize) {
			this.bytes = bytes;
			this.samples = samples;
			this.streamInfo = streamInfo;
			this.blockSize = blockSize;
		}
	}


	// Encodes a small stereo 16-bit stream whose contents mix silence, ramps, and noise,
	// with enough samples to yield several frames.
	static FlacFile makeSmall() throws IOException {
		int numSamples = 3000;
		int blockSize = 256;
		int[][] samples = makePattern(numSamples, 2, 16, 101);
		return encode(samples, 16, 44100, blockSize);
	}


	// Encodes a long mono stream whose size is well over the 100,000-byte threshold used by
	// FlacDecoder's binary-search seek path, so that code is exercised end to end.
	static FlacFile makeLarge() throws IOException {
		int numSamples = 400000;
		int blockSize = 4096;
		int[][] samples = makePattern(numSamples, 1, 16, 202);
		return encode(samples, 16, 48000, blockSize);
	}


	private static int[][] makePattern(int numSamples, int numChannels, int depth, int seed) {
		java.util.Random rnd = new java.util.Random(seed);
		int max = (1 << (depth - 1)) - 1;
		int[][] samples = new int[numChannels][numSamples];
		for (int i = 0; i < numSamples; i++) {
			for (int ch = 0; ch < numChannels; ch++) {
				int v;
				int region = i % 900;
				if (region < 300)
					v = 0;                                  // Silence -> constant subframes
				else if (region < 600)
					v = (i * (ch + 1) * 7) % 1000 - 500;    // Ramp -> predictable/LPC-friendly
				else
					v = rnd.nextInt(20001) - 10000;         // Bounded noise -> varied Rice residuals
				if (v > max) v = max;
				if (v < -max - 1) v = -max - 1;
				samples[ch][i] = v;
			}
		}
		return samples;
	}


	private static FlacFile encode(int[][] samples, int depth, int rate, int blockSize) throws IOException {
		// ByteArrayOutputStream.close() is a no-op, so BitOutputStream closing it is harmless.
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		// Silence the encoder's per-frame progress chatter during tests
		java.io.PrintStream oldErr = System.err;
		try (BitOutputStream out = new BitOutputStream(bout)) {
			System.setErr(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
			out.writeInt(32, 0x664C6143);  // "fLaC"
			StreamInfo info = new StreamInfo();
			info.minBlockSize = blockSize;  // Must be populated before the stream-info block is written
			info.maxBlockSize = blockSize;
			info.sampleRate = rate;
			info.numChannels = samples.length;
			info.sampleDepth = depth;
			info.numSamples = samples[0].length;
			info.md5Hash = StreamInfo.getMd5Hash(samples, depth);
			info.write(true, out);  // Single, last metadata block
			new FlacEncoder(info, samples, blockSize, SubframeEncoder.SearchOptions.SUBSET_BEST, out);
			out.flush();
			return new FlacFile(bout.toByteArray(), samples, info, blockSize);
		} finally {
			System.setErr(oldErr);
		}
	}


	private FlacTestData() {}

}

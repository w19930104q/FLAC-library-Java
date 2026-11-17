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
import java.util.Arrays;
import io.nayuki.flac.common.FrameInfo;
import io.nayuki.flac.common.StreamInfo;
import static io.nayuki.flac.decode.TestUtil.*;


/**
 * End-to-end behavior of {@link FrameDecoder} driven through each {@link FlacLowLevelInput}
 * implementation over a real encoded FLAC stream. These tests focus on the interaction the
 * task calls out: continuous frame decoding interleaved with seeks, including seeking back
 * to an already-decoded frame and seeking to the end of the stream.
 *
 * <p>The same encoded bytes and the same sequence of operations are used for both
 * implementations, and their decoded sample output is compared against the original
 * samples and against each other.</p>
 */
final class FrameDecoderSeekTest {

	static void runAll(String name, FlacInputFactory factory) throws IOException {
		System.out.println("== Frame decode/seek tests: " + name + " ==");
		int before = passedChecks;
		FlacTestData.FlacFile file = FlacTestData.makeSmall();
		testSequentialDecodeMatchesOriginal(factory, file);
		testSeekBackRepeatsFrame(factory, file);
		testSeekForwardThenContinue(factory, file);
		testSeekAfterMisalignedRead(factory, file);
		testSeekToEndReportsEof(factory, file);
		System.out.println("   passed " + (passedChecks - before) + " checks");
	}


	// Decode every frame from the origin and check every sample against the encoder's input.
	private static void testSequentialDecodeMatchesOriginal(FlacInputFactory factory, FlacTestData.FlacFile file)
			throws IOException {
		int channels = file.samples.length;
		int numSamples = file.samples[0].length;
		int[][] decoded = new int[channels][numSamples];
		try (FlacLowLevelInput in = factory.create(file.bytes)) {
			StreamInfo info = openMetadata(in);
			FrameDecoder dec = new FrameDecoder(in, info.sampleDepth);
			int[][] block = new int[channels][file.blockSize];
			int off = 0;
			int frameCount = 0;
			long lastFrameEnd = 0;
			while (true) {
				FrameInfo meta = dec.readFrame(block, 0);
				if (meta == null)
					break;
				lastFrameEnd = in.getPosition();
				for (int ch = 0; ch < channels; ch++)
					System.arraycopy(block[ch], 0, decoded[ch], off, meta.blockSize);
				off += meta.blockSize;
				frameCount++;
			}
			checkEq(off, numSamples, "all samples decoded sequentially");
			check(frameCount > 1, "stream contains multiple frames");
			checkEq(lastFrameEnd, file.bytes.length, "last frame ends exactly at the true end of stream");
			checkEq(in.readByte(), -1, "EOF sentinel after full decode");
		}
		check(samplesEqual(decoded, file.samples), "sequential decode matches original samples");
	}


	// Decode the first frames, seek back to the first frame's offset, and confirm the frame
	// decodes byte-for-byte identically on the second pass.
	private static void testSeekBackRepeatsFrame(FlacInputFactory factory, FlacTestData.FlacFile file)
			throws IOException {
		int channels = file.samples.length;
		try (FlacLowLevelInput in = factory.create(file.bytes)) {
			StreamInfo info = openMetadata(in);
			FrameDecoder dec = new FrameDecoder(in, info.sampleDepth);
			int[][] first = new int[channels][file.blockSize];
			FrameInfo meta0 = dec.readFrame(first, 0);
			check(meta0 != null, "first frame decoded");
			long firstFramePos = in.getPosition() - meta0.frameSize;

			// Decode several more frames to advance well past internal buffer boundaries
			int[][] junk = new int[channels][file.blockSize];
			for (int i = 0; i < 6; i++)
				check(dec.readFrame(junk, 0) != null, "intermediate frame decoded");

			in.seekTo(firstFramePos);
			checkEq(in.getPosition(), firstFramePos, "seeked back to first frame offset");
			checkEq(in.getBitPosition(), 0, "seek resets bit position");
			checkEq(in.getCrc16(), 0, "seek resets CRC-16");

			int[][] repeated = new int[channels][file.blockSize];
			FrameInfo meta1 = dec.readFrame(repeated, 0);
			check(meta1 != null, "first frame decoded again after seek");
			checkEq(meta1.frameSize, meta0.frameSize, "repeated frame has same size");
			check(samplesEqual(repeated, first), "repeated frame samples identical");
			check(samplesEqual(repeated, slice(file.samples, 0, file.blockSize)),
				"repeated frame matches original samples");
		}
	}


	// Seek to a frame in the middle of the stream and continue decoding to the end;
	// the tail must equal the original samples for the same sample range.
	private static void testSeekForwardThenContinue(FlacInputFactory factory, FlacTestData.FlacFile file)
			throws IOException {
		int channels = file.samples.length;
		int numSamples = file.samples[0].length;
		try (FlacLowLevelInput in = factory.create(file.bytes)) {
			StreamInfo info = openMetadata(in);
			FrameDecoder dec = new FrameDecoder(in, info.sampleDepth);

			// Locate a frame roughly two thirds through by decoding offsets
			int targetFrame = (numSamples / file.blockSize) * 2 / 3;
			int targetSample = targetFrame * file.blockSize;
			long frameOffset = findFrameOffset(in, dec, file, targetFrame);

			in.seekTo(frameOffset);
			int[][] tail = new int[channels][numSamples - targetSample];
			int off = 0;
			while (true) {
				FrameInfo meta = dec.readFrame(tail, off);
				if (meta == null)
					break;
				off += meta.blockSize;
			}
			checkEq(off, tail[0].length, "tail fully decoded after forward seek");
			check(samplesEqual(tail, slice(file.samples, targetSample, numSamples)),
				"post-seek tail matches original samples");
		}
	}


	// Consume a few bits mid-stream (as a caller inspecting bytes might), then seek to a
	// frame boundary and decode: the seek must clear the partial byte and CRC state cleanly.
	private static void testSeekAfterMisalignedRead(FlacInputFactory factory, FlacTestData.FlacFile file)
			throws IOException {
		int channels = file.samples.length;
		try (FlacLowLevelInput in = factory.create(file.bytes)) {
			StreamInfo info = openMetadata(in);
			FrameDecoder dec = new FrameDecoder(in, info.sampleDepth);
			int[][] first = new int[channels][file.blockSize];
			FrameInfo meta0 = dec.readFrame(first, 0);
			check(meta0 != null, "setup frame decoded");
			long nextFramePos = in.getPosition();

			// Deliberately enter a misaligned, CRC-dirty state
			in.readUint(5);
			checkEq(in.getBitPosition(), 5, "test setup reaches misaligned state");

			// Seeking to the next frame must make decoding work as if the partial read never happened
			in.seekTo(nextFramePos);
			int[][] second = new int[channels][file.blockSize];
			FrameInfo meta1 = dec.readFrame(second, 0);
			check(meta1 != null, "frame decodes after seek from misaligned state");
			check(samplesEqual(second, slice(file.samples, file.blockSize, 2 * file.blockSize)),
				"frame after misaligned-read seek matches original samples");
		}
	}


	// Seek directly to the last byte and to the exact end: frame decoding must report a
	// clean EOF (null) rather than throw, and a subsequent seek back recovers the stream.
	private static void testSeekToEndReportsEof(FlacInputFactory factory, FlacTestData.FlacFile file)
			throws IOException {
		int channels = file.samples.length;
		try (FlacLowLevelInput in = factory.create(file.bytes)) {
			StreamInfo info = openMetadata(in);
			FrameDecoder dec = new FrameDecoder(in, info.sampleDepth);

			in.seekTo(file.bytes.length);
			check(dec.readFrame(new int[channels][file.blockSize], 0) == null,
				"frame decode at exact end returns null (clean EOF)");

			in.seekTo(file.bytes.length - 1);
			// One trailing byte cannot start a valid frame; ensure it does not silently succeed.
			boolean failed = false;
			try {
				dec.readFrame(new int[channels][file.blockSize], 0);
			} catch (DataFormatException | java.io.EOFException e) {
				failed = true;
			}
			check(failed, "frame decode starting one byte before end cannot succeed");

			// Recovery: seek back to the origin and decode the first frame successfully
			in.seekTo(0);
			openMetadata(in);
			int[][] first = new int[channels][file.blockSize];
			FrameInfo meta = dec.readFrame(first, 0);
			check(meta != null, "stream recovers and decodes after end-of-stream seeks");
			check(samplesEqual(first, slice(file.samples, 0, file.blockSize)),
				"recovered first frame matches original samples");
		}
	}



	/*---- Helpers ----*/

	// Reads the "fLaC" marker and the single stream-info metadata block, returning parsed info.
	private static StreamInfo openMetadata(FlacLowLevelInput in) throws IOException {
		if (in.readUint(32) != 0x664C6143)
			throw new AssertionError("missing fLaC marker");
		int last = in.readUint(1);
		int type = in.readUint(7);
		int len = in.readUint(24);
		if (last != 1 || type != 0 || len != 34)
			throw new AssertionError("unexpected metadata layout in test fixture");
		byte[] payload = new byte[34];
		in.readFully(payload);
		return new StreamInfo(payload);
	}


	// Decodes frames from the origin until reaching the given frame index and returns its byte offset.
	private static long findFrameOffset(FlacLowLevelInput in, FrameDecoder dec,
			FlacTestData.FlacFile file, int targetFrame) throws IOException {
		in.seekTo(0);
		openMetadata(in);
		int[][] buf = new int[file.samples.length][file.blockSize];
		long offset = in.getPosition();
		for (int i = 0; i < targetFrame; i++) {
			FrameInfo meta = dec.readFrame(buf, 0);
			if (meta == null)
				throw new AssertionError("ran out of frames before target");
			offset = in.getPosition();
		}
		return offset;
	}


	private static int[][] slice(int[][] samples, int from, int to) {
		int[][] result = new int[samples.length][to - from];
		for (int ch = 0; ch < samples.length; ch++)
			System.arraycopy(samples[ch], from, result[ch], 0, to - from);
		return result;
	}


	private static boolean samplesEqual(int[][] a, int[][] b) {
		return Arrays.deepEquals(a, b);
	}


	private FrameDecoderSeekTest() {}

}

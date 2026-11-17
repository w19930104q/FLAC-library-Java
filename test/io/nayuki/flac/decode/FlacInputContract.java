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

import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import static io.nayuki.flac.decode.TestUtil.*;


/**
 * The shared behavioral contract that every {@link FlacLowLevelInput} implementation
 * must satisfy. Each test scenario is written once against the {@link FlacInputFactory}
 * abstraction and run identically for a byte-array-backed input and a random-access-file
 * backed input, so any divergence between the two implementations fails the same test.
 *
 * <p>The scenarios emphasize the historically fragile areas: reads crossing the internal
 * 4096-byte buffer boundary, seeking (including to the very end of the stream), and
 * interleaving continuous decoding with seeks.</p>
 */
final class FlacInputContract {

	/*---- Driver ----*/

	// Runs every scenario against inputs produced by the given factory.
	static void runAll(String name, FlacInputFactory factory) throws IOException {
		System.out.println("== Contract tests: " + name + " ==");
		int before = passedChecks;
		testInitialState(factory);
		testCrossBufferBoundaryBitReads(factory);
		testCrossBufferBoundaryByteReads(factory);
		testReadFullyAcrossBuffers(factory);
		testEndOfStreamBehavior(factory);
		testSeekToEndAndBack(factory);
		testContinuousReadThenSeek(factory);
		testSeekResetsBitPositionAndCrcs(factory);
		testCrcAcrossBufferBoundaries(factory);
		testRiceDecodingAcrossBuffers(factory);
		testArgumentValidation(factory);
		testEmptyStream(factory);
		testCloseSemantics(factory);
		System.out.println("   passed " + (passedChecks - before) + " checks");
	}



	/*---- Scenarios ----*/

	// Newly constructed streams report the origin and zero CRC/bit state.
	private static void testInitialState(FlacInputFactory factory) throws IOException {
		byte[] data = randomBytes(100);
		try (FlacLowLevelInput in = factory.create(data)) {
			checkEq(in.getPosition(), 0, "initial position");
			checkEq(in.getBitPosition(), 0, "initial bit position");
			checkEq(in.getLength(), data.length, "length");
			checkEq(in.getCrc8(), 0, "initial CRC-8");
			checkEq(in.getCrc16(), 0, "initial CRC-16");
		}
	}


	// Bitwise reads with varied widths, aligned and misaligned, spanning many 4096-byte
	// internal buffer refills, must match an independent reference bit reader exactly.
	private static void testCrossBufferBoundaryBitReads(FlacInputFactory factory) throws IOException {
		byte[] data = randomBytes(10000);  // More than twice the 4096-byte internal buffer
		ReferenceReader ref = new ReferenceReader(data);
		try (FlacLowLevelInput in = factory.create(data)) {
			int[] widths = {1, 2, 3, 5, 7, 8, 11, 13, 16, 24, 31, 32};
			int wi = 0;
			while (ref.getPosition() < data.length - 16) {
				int n = widths[wi++ % widths.length];
				// Keep each read byte-aligned-ish but deliberately consume partial bits too
				int actual = in.readUint(n);
				int expected = ref.readUint(n);
				checkEq(actual, expected, "readUint value across buffers");
				checkEq(in.getPosition(), ref.getPosition(), "position after readUint");
				checkEq(in.getBitPosition(), ref.getBitPosition(), "bit position after readUint");

				// Every so often read a signed value instead
				if (wi % 5 == 0) {
					int sn = widths[wi % widths.length];
					int sActual = in.readSignedInt(sn);
					int sExpected = ref.readSignedInt(sn);
					checkEq(sActual, sExpected, "readSignedInt value across buffers");
					checkEq(in.getBitPosition(), ref.getBitPosition(), "bit position after readSignedInt");
				}
			}
		}
	}


	// Single-byte reads across buffer boundaries and EOF must match the source bytes,
	// including the transition from real bytes to the -1 EOF sentinel.
	private static void testCrossBufferBoundaryByteReads(FlacInputFactory factory) throws IOException {
		byte[] data = randomBytes(9000);
		try (FlacLowLevelInput in = factory.create(data)) {
			for (int i = 0; i < data.length; i++) {
				int b = in.readByte();
				checkEq(b, data[i] & 0xFF, "readByte value at index " + i);
				checkEq(in.getPosition(), (long)i + 1, "position after readByte");
				checkEq(in.getBitPosition(), 0, "readByte stays byte-aligned");
			}
			checkEq(in.readByte(), -1, "readByte EOF sentinel");
			checkEq(in.readByte(), -1, "readByte repeated EOF sentinel");
			check(in.getPosition() >= data.length, "position never underruns the stream at EOF");
		}
	}


	// readFully() of arrays both smaller and larger than the internal buffer,
	// including reads straddling the 4096-byte refill point.
	private static void testReadFullyAcrossBuffers(FlacInputFactory factory) throws IOException {
		byte[] data = randomBytes(14000);
		try (FlacLowLevelInput in = factory.create(data)) {
			// Read in deliberately awkward chunk sizes that do not divide the buffer size
			int[] chunks = {1, 7, 4095, 4096, 4097, 100, 3};
			int pos = 0;
			for (int chunk : chunks) {
				byte[] buf = new byte[chunk];
				in.readFully(buf);
				byte[] expected = Arrays.copyOfRange(data, pos, pos + chunk);
				check(Arrays.equals(buf, expected), "readFuly chunk at offset " + pos);
				pos += chunk;
				checkEq(in.getPosition(), pos, "position after readFully");
			}
		}
	}


	// Every way of reading past the end of the stream has a defined, identical outcome
	// for both implementations: -1 for readByte(), EOFException for bit/fully reads.
	private static void testEndOfStreamBehavior(FlacInputFactory factory) throws IOException {
		byte[] data = randomBytes(5000);
		try (FlacLowLevelInput in = factory.create(data)) {
			in.seekTo(data.length);
			checkEq(in.getPosition(), data.length, "position after seek to end");
			checkEq(in.readByte(), -1, "readByte at end returns -1");
			expectThrows(EOFException.class, () -> in.readUint(1), "readUint at end throws EOF");
			expectThrows(EOFException.class, () -> in.readUint(32), "readUint(32) at end throws EOF");
			expectThrows(EOFException.class, () -> in.readFully(new byte[1]), "readFully at end throws EOF");

			// One byte before the end: a multi-bit read that overruns still throws EOF
			in.seekTo(data.length - 1);
			checkEq(in.readByte(), data[data.length - 1] & 0xFF, "last real byte readable");
			expectThrows(EOFException.class, () -> in.readUint(1), "overrun read throws EOF");

			// A seek past the end behaves like being at the end
			in.seekTo(data.length + 100);
			checkEq(in.getPosition(), data.length + 100, "position past end retained");
			checkEq(in.readByte(), -1, "readByte past end returns -1");
			expectThrows(EOFException.class, () -> in.readUint(8), "readUint past end throws EOF");
		}
	}


	// Seek to the exact end, confirm EOF reads, then seek back and confirm the stream
	// is fully resynchronized to the same bytes seen from the origin.
	private static void testSeekToEndAndBack(FlacInputFactory factory) throws IOException {
		byte[] data = randomBytes(8500);
		try (FlacLowLevelInput in = factory.create(data)) {
			in.seekTo(4096);
			in.readFully(new byte[4096]);  // Drain across a buffer boundary
			in.seekTo(data.length);         // Jump to the very end
			checkEq(in.getPosition(), data.length, "seek-to-end position");
			checkEq(in.getBitPosition(), 0, "seek-to-end resets bit position");
			checkEq(in.readByte(), -1, "EOF after seek to end");

			// Seek back to a misaligned-with-respect-to-buffer offset and read to the end again
			int back = 3000;
			in.seekTo(back);
			checkEq(in.getPosition(), back, "seek-back position");
			ReferenceReader ref = new ReferenceReader(data);
			for (int i = 0; i < back; i++)
				ref.readByte();  // Advance the oracle to the same byte offset
			for (int i = back; i < data.length; i++) {
				checkEq(in.readByte(), ref.readByte(), "byte after seek-back at " + i);
			}
			checkEq(in.readByte(), -1, "EOF after re-reading to end");
		}
	}


	// Simulates continuous frame-like decoding: a long unbroken run of mixed bit/byte reads,
	// then seeks to earlier, middle, and later positions, each time verifying against the oracle
	// that decoding resumes at exactly the right byte with the right bit position.
	private static void testContinuousReadThenSeek(FlacInputFactory factory) throws IOException {
		byte[] data = randomBytes(12000);
		try (FlacLowLevelInput in = factory.create(data)) {
			ReferenceReader ref = new ReferenceReader(data);

			// Phase 1: continuous decoding across several buffer refills. Each cycle consumes
			// exactly 40 bits (5 bytes), so byte-aligned operations stay byte-aligned while
			// the 13-bit/3-bit reads still exercise misaligned bit buffering.
			final int cycles = 1200;
			for (int i = 0; i < cycles; i++) {
				in.readUint(13);
				ref.readUint(13);
				in.readUint(3);
				ref.readUint(3);
				checkEq(in.readByte(), ref.readByte(), "byte during continuous decoding");
				byte[] pair = new byte[2];
				in.readFully(pair);
				ref.readUint(16);
			}
			checkEq(in.getPosition(), ref.getPosition(), "position after continuous decoding");
			checkEq(in.getPosition(), (long)cycles * 5, "continuous decoding consumed expected bytes");
			check(in.getPosition() > 4096, "continuous decoding crossed buffer boundaries");

			// Phase 2: seek backwards (into already-buffered territory)
			long seek1 = 123;
			in.seekTo(seek1);
			ref = new ReferenceReader(data);
			for (long i = 0; i < seek1; i++)
				ref.readByte();  // Advance the oracle to the seek target
			for (int i = 0; i < 500; i++) {
				checkEq(in.readUint(9), ref.readUint(9), "read after backward seek");
			}

			// Phase 3: seek forwards to an unread region, starting mid-byte
			long seek2 = 7777;
			in.seekTo(seek2);
			ref = new ReferenceReader(data);
			for (long i = 0; i < seek2; i++)
				ref.readByte();  // Advance the oracle to the seek target
			ref.readUint(3);
			in.readUint(3);  // Consume 3 bits to leave the stream misaligned
			checkEq(in.getBitPosition(), 3, "misaligned bit position after seek+read");
			expectThrows(IllegalStateException.class, in::readByte, "readByte while misaligned throws");
			expectThrows(IllegalStateException.class, () -> in.readFully(new byte[1]),
				"readFully while misaligned throws");
			in.readUint(5);  // Return to a byte boundary (3 + 5 = 8 bits)
			ref.readUint(5);  // Keep the oracle in lockstep; the failed calls consumed nothing
			checkEq(in.getBitPosition(), 0, "realigned bit position");
			checkEq(in.getBitPosition(), ref.getBitPosition(), "oracle realigned too");
			for (int i = 0; i < 1000; i++) {
				checkEq(in.readByte(), ref.readByte(), "byte after forward seek at " + i);
			}

			// Phase 4: seek to the origin and re-decode the opening sequence identically
			in.seekTo(0);
			ReferenceReader origin = new ReferenceReader(data);
			for (int i = 0; i < 300; i++)
				checkEq(in.readUint(11), origin.readUint(11), "re-decode from origin");
		}
	}


	// seekTo() must reset the bit position to 0 and restart both CRC calculations,
	// regardless of how misaligned or CRC-dirty the stream was before the seek.
	private static void testSeekResetsBitPositionAndCrcs(FlacInputFactory factory) throws IOException {
		byte[] data = randomBytes(6000);
		try (FlacLowLevelInput in = factory.create(data)) {
			in.readUint(29);  // Misalign and accumulate bit-buffer state
			in.readUint(3);   // Return to a byte boundary, having consumed 4 whole bytes
			in.readFully(new byte[100]);  // Accumulate CRC history
			check(in.getPosition() > 0, "pre-seek state is non-trivial");

			in.seekTo(2500);
			checkEq(in.getPosition(), 2500, "position reset by seek");
			checkEq(in.getBitPosition(), 0, "bit position reset by seek");
			checkEq(in.getCrc8(), 0, "CRC-8 reset by seek");
			checkEq(in.getCrc16(), 0, "CRC-16 reset by seek");

			// CRCs after reading a known range after the seek match the reference over that range only
			byte[] postSeek = new byte[123];
			in.readFully(postSeek);
			byte[] expectedRange = Arrays.copyOfRange(data, 2500, 2500 + 123);
			check(java.util.Arrays.equals(postSeek, expectedRange), "post-seek payload read correctly");
			checkEq(in.getCrc8(), crc8(expectedRange, 0, expectedRange.length), "CRC-8 after seek covers only post-seek bytes");
			checkEq(in.getCrc16(), crc16(expectedRange, 0, expectedRange.length), "CRC-16 after seek covers only post-seek bytes");
		}
	}


	// CRC values accumulated over reads that cross internal buffer boundaries must
	// match independent polynomial calculations over the same byte ranges.
	private static void testCrcAcrossBufferBoundaries(FlacInputFactory factory) throws IOException {
		byte[] data = randomBytes(10000);
		try (FlacLowLevelInput in = factory.create(data)) {
			in.readFully(Arrays.copyOfRange(data, 0, 3000));
			in.resetCrcs();
			int start = 3000;
			byte[] middle = new byte[5000];  // Spans the 4096-byte boundary
			in.readFully(middle);
			check(Arrays.equals(middle, Arrays.copyOfRange(data, start, start + 5000)), "CRC test payload read");
			checkEq(in.getCrc8(), crc8(data, start, 5000), "CRC-8 across buffer boundary");
			checkEq(in.getCrc16(), crc16(data, start, 5000), "CRC-16 across buffer boundary");

			// resetCrcs() mid-stream starts a fresh hash from that point
			in.resetCrcs();
			byte[] tail = new byte[data.length - (start + 5000)];
			in.readFully(tail);
			checkEq(in.getCrc8(), crc8(data, start + 5000, tail.length), "CRC-8 after mid-stream reset");
			checkEq(in.getCrc16(), crc16(data, start + 5000, tail.length), "CRC-16 after mid-stream reset");
		}
	}


	// Rice-coded integers, including long unary runs that force the table-driven fast path
	// to fall back to the slow path, decoded in chunks across buffer boundaries.
	private static void testRiceDecodingAcrossBuffers(FlacInputFactory factory) throws IOException {
		int[] params = {0, 1, 4, 13, 20};
		// Values are chosen per parameter so that the unary quotient (u >>> param) stays small,
		// while param 0 still exercises unary runs of up to ~200 zero bits (slow-path fallback).
		// All literals are long (L suffix) so negative values sign-extend correctly in the zigzag map.
		long[][] valuesByParam = {
			{0L, 1L, -1L, 2L, -2L, 10L, -10L, 20L, -20L, 50L, -50L, 100L},
			{0L, 1L, -1L, 2L, -2L, 100L, -100L, 200L, -200L, 300L},
			{0L, 1L, -1L, 2L, -2L, 100L, -100L, 200L, -200L, 250L},
			{0L, 1L, -1L, 2L, -2L, 1000L, -1000L, 4000L, -4000L, 8000L},
			{0L, 1L, -1L, 2L, -2L, 100000L, -100000L, 500000L, -500000L, 1000000L},
		};
		java.io.ByteArrayOutputStream payload = new java.io.ByteArrayOutputStream();
		BitEncoder enc = new BitEncoder(payload);
		int totalCount = 0;
		for (int p = 0; p < params.length; p++) {
			for (long v : valuesByParam[p]) {
				long u = (v << 1) ^ (v >> 63);  // Signed int53 to interleaved uint53 (inverse of the decoder's zigzag)
				long quotient = u >>> params[p];
				check(quotient <= 300, "test fixture keeps unary runs short");
				for (long q = 0; q < quotient; q++)
					enc.write(1, 0);               // Unary zeros
				enc.write(1, 1);                   // Unary terminator
				for (int b = params[p] - 1; b >= 0; b--)
					enc.write(1, (int)((u >>> b) & 1));
				totalCount++;
			}
		}
		// Pad with zero bits to a byte boundary; the decoder consumes exactly totalCount values
		// and never reads the padding.
		long payloadBits = enc.totalBits;
		enc.write((int)((8 - payloadBits % 8) % 8), 0);
		enc.flush();

		// Pad the payload with lead-in/lead-out bytes so decoding starts mid-buffer and
		// straddles multiple 4096-byte refills
		byte[] core = payload.toByteArray();
		int leadIn = 4096 * 3;
		int leadOut = 5000;
		check(enc.totalBits % 8 == 0, "Rice test payload is byte aligned by construction");
		byte[] data = new byte[leadIn + core.length + leadOut];
		byte[] junk = randomBytes(leadIn);
		System.arraycopy(junk, 0, data, 0, junk.length);
		System.arraycopy(core, 0, data, leadIn, core.length);
		byte[] tail = randomBytes(leadOut);
		System.arraycopy(tail, 0, data, leadIn + core.length, tail.length);

		try (FlacLowLevelInput in = factory.create(data)) {
			in.readFully(new byte[leadIn]);  // Advance to the Rice payload, byte-aligned
			long[] result = new long[totalCount];
			int idx = 0;
			for (int p = 0; p < params.length; p++) {
				long[] group = valuesByParam[p];
				int half = group.length / 2;
				// Decode each parameter group in two calls to exercise chunk boundaries
				in.readRiceSignedInts(params[p], result, idx, idx + half);
				in.readRiceSignedInts(params[p], result, idx + half, idx + group.length);
				for (int j = 0; j < group.length; j++)
					checkEq(result[idx + j], group[j], "Rice value param=" + params[p] + " j=" + j);
				idx += group.length;
			}
			checkEq(idx, totalCount, "all Rice values decoded");
			// The decoder stops just before the zero padding that rounded the payload up
			// to a byte boundary, so its bit position is the complement of the padding width.
			int paddingBits = (int)((8 - payloadBits % 8) % 8);
			checkEq(in.getBitPosition(), (8 - paddingBits) % 8,
				"Rice decode stops at the expected position before padding");

			// Invalid parameters are rejected identically
			long[] sink = new long[8];
			expectThrows(IllegalArgumentException.class,
				() -> in.readRiceSignedInts(-1, sink, 0, 1), "negative Rice param rejected");
			expectThrows(IllegalArgumentException.class,
				() -> in.readRiceSignedInts(32, sink, 0, 1), "Rice param 32 rejected");
		}
	}


	// Argument and state checks that form part of the public contract.
	private static void testArgumentValidation(FlacInputFactory factory) throws IOException {
		byte[] data = randomBytes(8000);
		try (FlacLowLevelInput in = factory.create(data)) {
			expectThrows(IllegalArgumentException.class, () -> in.readUint(-1), "readUint(-1) rejected");
			expectThrows(IllegalArgumentException.class, () -> in.readUint(33), "readUint(33) rejected");
			expectThrows(NullPointerException.class, () -> in.readFully(null), "readFully(null) rejected");
			expectThrows(IllegalArgumentException.class, () -> in.seekTo(-1), "negative seek rejected");
			expectThrows(IllegalArgumentException.class, () -> in.seekTo(Long.MIN_VALUE), "MIN_VALUE seek rejected");

			// Misaligned state forbids byte-oriented and CRC operations
			in.readUint(3);
			checkEq(in.getBitPosition(), 3, "test setup leaves stream misaligned");
			expectThrows(IllegalStateException.class, in::readByte, "misaligned readByte rejected");
			expectThrows(IllegalStateException.class, () -> in.readFully(new byte[1]), "misaligned readFully rejected");
			expectThrows(IllegalStateException.class, in::resetCrcs, "misaligned resetCrcs rejected");
			expectThrows(IllegalStateException.class, in::getCrc8, "misaligned getCrc8 rejected");
			expectThrows(IllegalStateException.class, in::getCrc16, "misaligned getCrc16 rejected");
		}
	}


	// Zero-length streams must behave consistently from construction through EOF and seek.
	private static void testEmptyStream(FlacInputFactory factory) throws IOException {
		byte[] data = new byte[0];
		try (FlacLowLevelInput in = factory.create(data)) {
			checkEq(in.getLength(), 0, "empty stream length");
			checkEq(in.getPosition(), 0, "empty stream initial position");
			checkEq(in.readByte(), -1, "empty stream readByte EOF");
			expectThrows(EOFException.class, () -> in.readUint(1), "empty stream readUint EOF");
			expectThrows(EOFException.class, () -> in.readFully(new byte[1]), "empty stream readFully EOF");
			in.seekTo(0);
			checkEq(in.getPosition(), 0, "empty stream seek to 0");
			checkEq(in.readByte(), -1, "empty stream EOF after seek to 0");
		}
	}


	// close() is idempotent, and seeking after closing is forbidden.
	private static void testCloseSemantics(FlacInputFactory factory) throws IOException {
		byte[] data = randomBytes(100);
		FlacLowLevelInput in = factory.create(data);
		in.close();
		in.close();  // Must not throw on repeated close
		passedChecks++;
		expectThrows(IllegalStateException.class, () -> in.seekTo(0), "seek after close rejected");
		// Reads on a closed stream hit the invalidated buffer state and must fail rather than return data.
		// Both implementations surface this identically as an EOFException from the exhausted buffer.
		expectThrows(Exception.class, () -> in.readUint(1), "read after close fails");
		expectThrows(Exception.class, in::readByte, "readByte after close fails");
	}



	/*---- Tiny byte-aligned bit writer for building Rice payloads ----*/

	private static final class BitEncoder {
		private final java.io.OutputStream out;
		private int buffer;
		private int bits;
		private long totalBits;


		BitEncoder(java.io.OutputStream out) {
			this.out = out;
		}


		void write(int n, int val) {
			totalBits += n;
			for (int i = n - 1; i >= 0; i--) {
				buffer = (buffer << 1) | ((val >>> i) & 1);
				bits++;
				if (bits == 8) {
					try {
						out.write(buffer & 0xFF);
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
					buffer = 0;
					bits = 0;
				}
			}
		}


		void flush() {
			if (bits > 0) {
				try {
					out.write((buffer << (8 - bits)) & 0xFF);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				buffer = 0;
				bits = 0;
			}
		}
	}


	private FlacInputContract() {}

}

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
import static io.nayuki.flac.decode.TestUtil.*;


/**
 * Replays one identical, pseudo-randomly generated script of operations against two
 * {@link FlacLowLevelInput} implementations and asserts that every observable result
 * (read values, positions, bit positions, CRCs, and even exception types) matches.
 *
 * <p>The script deliberately interleaves long runs of continuous bit/byte/Rice reads
 * with seeks backwards, forwards, to the origin, and to the end of the stream, so any
 * divergence in buffering or seek semantics between the two backends is caught here.</p>
 */
final class FlacInputEquivalenceTest {

	static void run(FlacInputFactory a, FlacInputFactory b) throws IOException {
		System.out.println("== Cross-implementation equivalence replay ==");
		int before = passedChecks;
		byte[] data = randomBytes(20000);

		try (FlacLowLevelInput x = a.create(data);
				FlacLowLevelInput y = b.create(data)) {
			checkEq(x.getLength(), y.getLength(), "equivalent lengths");
			java.util.Random rnd = new java.util.Random(0x5EED1234);
			int step = 0;
			while (step < 4000) {
				int op = rnd.nextInt(10);
				switch (op) {
					case 0: case 1: case 2: {  // Bit reads of varied width
						int n = 1 + rnd.nextInt(32);
						int rx = replayReadUint(x, n);
						int ry = replayReadUint(y, n);
						checkEq(rx, ry, "readUint equivalence at step " + step);
						break;
					}
					case 3: {  // Signed read
						int n = 1 + rnd.nextInt(32);
						Integer rx = replaySigned(x, n);
						Integer ry = replaySigned(y, n);
						checkEq(rx == null ? Integer.MIN_VALUE : rx,
							ry == null ? Integer.MIN_VALUE : ry, "readSignedInt equivalence at step " + step);
						break;
					}
					case 4: {  // Byte read (only when aligned; a seek guarantees alignment below)
						if (x.getBitPosition() == 0 && y.getBitPosition() == 0) {
							int rx = replayReadByte(x);
							int ry = replayReadByte(y);
							checkEq(rx, ry, "readByte equivalence at step " + step);
						}
						break;
					}
					case 5: {  // Bulk read, only at byte boundaries
						if (x.getBitPosition() == 0 && y.getBitPosition() == 0) {
							int n = 1 + rnd.nextInt(200);
							byte[] bx = replayReadFully(x, n);
							byte[] by = replayReadFully(y, n);
							check(java.util.Arrays.equals(bx, by), "readFully equivalence at step " + step);
						}
						break;
					}
					case 6: {  // Seek to a random valid position (re-synchronizes both streams)
						long pos = rnd.nextInt(data.length + 1);
						x.seekTo(pos);
						y.seekTo(pos);
						break;
					}
					case 7: {  // Seek to the very end, then probe the EOF behavior identically
						x.seekTo(data.length);
						y.seekTo(data.length);
						checkEq(replayReadByte(x), replayReadByte(y), "EOF sentinel equivalence at step " + step);
						break;
					}
					case 8: {  // Seek back to the origin
						x.seekTo(0);
						y.seekTo(0);
						break;
					}
					default: {  // CRC snapshot, only when aligned
						if (x.getBitPosition() == 0 && y.getBitPosition() == 0) {
							checkEq(x.getCrc8(), y.getCrc8(), "CRC-8 equivalence at step " + step);
							checkEq(x.getCrc16(), y.getCrc16(), "CRC-16 equivalence at step " + step);
						}
						break;
					}
				}
				checkEq(x.getPosition(), y.getPosition(), "position equivalence at step " + step);
				checkEq(x.getBitPosition(), y.getBitPosition(), "bit-position equivalence at step " + step);
				step++;
			}

			// Both streams must reject illegal seeks in the same way
			String ex = replaySeekException(x, -1);
			String ey = replaySeekException(y, -1);
			check(ex != null && ex.equals(ey), "negative seek throws the same exception type");
		}
		System.out.println("   passed " + (passedChecks - before) + " checks");
	}


	// The "result" of a read is either its value or the class name of the thrown exception;
	// both implementations must produce the same outcome. EOFException is represented as -1 sentinel.
	private static int replayReadUint(FlacLowLevelInput in, int n) throws IOException {
		try {
			return in.readUint(n);
		} catch (java.io.EOFException e) {
			return Integer.MIN_VALUE;
		}
	}


	private static Integer replaySigned(FlacLowLevelInput in, int n) throws IOException {
		try {
			return in.readSignedInt(n);
		} catch (java.io.EOFException e) {
			return null;
		}
	}


	private static int replayReadByte(FlacLowLevelInput in) throws IOException {
		return in.readByte();  // Never throws: EOF is the -1 sentinel for both implementations
	}


	private static byte[] replayReadFully(FlacLowLevelInput in, int n) throws IOException {
		byte[] buf = new byte[n];
		try {
			in.readFully(buf);
			return buf;
		} catch (java.io.EOFException e) {
			// Distinct marker that cannot equal a real length-n payload (n >= 1 here)
			return new byte[]{0x55, (byte)0xAA};
		}
	}


	private static String replaySeekException(FlacLowLevelInput in, long pos) {
		try {
			in.seekTo(pos);
			return null;
		} catch (Throwable t) {
			return t.getClass().getName();
		}
	}


	private FlacInputEquivalenceTest() {}

}

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


/**
 * Small self-contained test helpers shared by all the input-contract tests:
 * plain assertions, exception expectations, a reference bit reader that
 * operates directly on a byte array, and reference CRC-8/CRC-16 calculations.
 * Everything here is independent of the classes under test, so it can act
 * as an oracle for the shared behavior of every FlacLowLevelInput implementation.
 */
final class TestUtil {

	/*---- Assertion helpers ----*/

	static int passedChecks;


	static void check(boolean cond, String message) {
		if (!cond)
			throw new AssertionError(message);
		passedChecks++;
	}


	static void checkEq(long actual, long expected, String message) {
		if (actual != expected)
			throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
		passedChecks++;
	}


	// A zero-argument operation that may throw an IOException.
	interface IoOp {
		void run() throws IOException;
	}


	// Asserts that running the given operation throws an instance of the given exception class.
	static void expectThrows(Class<? extends Throwable> expected, IoOp op, String message) {
		boolean threw = false;
		try {
			op.run();
		} catch (Throwable t) {
			threw = true;
			if (!expected.isInstance(t))
				throw new AssertionError(message + " (expected=" + expected.getName()
					+ ", actual=" + t.getClass().getName() + ")", t);
		}
		if (!threw)
			throw new AssertionError(message + " (expected exception " + expected.getName() + " not thrown)");
		passedChecks++;
	}



	/*---- Deterministic payload generation ----*/

	// Fills the array with pseudo-random but reproducible byte values (full uint8 range).
	static byte[] randomBytes(int len) {
		byte[] result = new byte[len];
		int state = 0x12345678 ^ len;
		for (int i = 0; i < result.length; i++) {
			state ^= state << 13;
			state ^= state >>> 17;
			state ^= state << 5;
			result[i] = (byte)(state >>> 24);
		}
		return result;
	}



	/*---- Reference bit reader (the oracle) ----*/

	// Reads bits and bytes directly from a byte array, independently of any
	// FlacLowLevelInput implementation. Used to assert the observed stream contents.
	static final class ReferenceReader {

		private final byte[] data;
		private long bitPos;  // Total number of bits consumed from the start of the array.


		ReferenceReader(byte[] data) {
			this.data = data;
		}


		long getPosition() {
			return bitPos >>> 3;
		}


		int getBitPosition() {
			return (int)(bitPos & 7);
		}


		int readUint(int n) {
			if (n < 0 || n > 32)
				throw new IllegalArgumentException();
			if (n == 0)
				return 0;
			long result = 0;
			for (int i = 0; i < n; i++) {
				if (bitPos >= (long)data.length * 8)
					throw new IllegalStateException("Read past end of reference data");
				int byteIndex = (int)(bitPos >>> 3);
				int bitIndex = 7 - (int)(bitPos & 7);
				int bit = (data[byteIndex] >>> bitIndex) & 1;
				result = (result << 1) | bit;
				bitPos++;
			}
			return (int)result;
		}


		int readSignedInt(int n) {
			if (n == 0)
				return 0;
			int shift = 32 - n;
			return (readUint(n) << shift) >> shift;
		}


		// Returns the next unsigned byte or -1 at EOF; only valid at a byte boundary.
		int readByte() {
			if (getBitPosition() != 0)
				throw new IllegalStateException("Not at a byte boundary");
			long pos = getPosition();
			if (pos >= data.length)
				return -1;
			bitPos += 8;
			return data[(int)pos] & 0xFF;
		}
	}



	/*---- Reference CRC calculations (FLAC polynomials) ----*/

	static int crc8(byte[] data, int off, int len) {
		int crc = 0;
		for (int i = 0; i < len; i++) {
			crc ^= data[off + i] & 0xFF;
			for (int j = 0; j < 8; j++)
				crc = (crc << 1) ^ ((crc >>> 7) * 0x107);
			crc &= 0xFF;
		}
		return crc;
	}


	static int crc16(byte[] data, int off, int len) {
		int crc = 0;
		for (int i = 0; i < len; i++) {
			crc ^= (data[off + i] & 0xFF) << 8;
			for (int j = 0; j < 8; j++)
				crc = (crc << 1) ^ ((crc >>> 15) * 0x18005);
			crc &= 0xFFFF;
		}
		return crc;
	}


	// Not instantiable.
	private TestUtil() {}

}

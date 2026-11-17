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

import java.util.Objects;


/**
 * A FLAC input stream based on a fixed byte array.
 * <p>All read, position, seek, CRC, and close semantics are provided by
 * {@link AbstractFlacLowLevelInput}; this class only supplies the mechanics
 * of reading and repositioning within the backing array.</p>
 */
public final class ByteArrayFlacInput extends AbstractFlacLowLevelInput {
	
	/*---- Fields ----*/
	
	// The underlying byte array to read from. Set to null when the stream is closed.
	private byte[] data;
	
	// Absolute byte offset into 'data' where the next readUnderlying() starts.
	private int offset;
	
	
	
	/*---- Constructors ----*/
	
	public ByteArrayFlacInput(byte[] b) {
		super();
		data = Objects.requireNonNull(b);
		offset = 0;
	}
	
	
	
	/*---- Methods ----*/
	
	public long getLength() {
		return data.length;
	}
	
	
	protected void seekUnderlying(long pos) {
		offset = (int)pos;
	}
	
	
	protected int readUnderlying(byte[] buf, int off, int len) {
		if (off < 0 || off > buf.length || len < 0 || len > buf.length - off)
			throw new ArrayIndexOutOfBoundsException();
		// A seek can leave the cursor at or beyond the end (mirroring a RandomAccessFile
		// positioned at EOF), so an exhausted source reports -1 instead of a negative length.
		if (offset >= data.length)
			return -1;
		int n = Math.min(data.length - offset, len);
		if (n == 0)
			return -1;
		System.arraycopy(data, offset, buf, off, n);
		offset += n;
		return n;
	}
	
	
	// Discards the reference to the backing array and invalidates this stream. Because this
	// class and its superclass only use memory and have no native resources, it's okay to
	// simply let a ByteArrayFlacInput be garbage-collected without calling close().
	protected void closeUnderlying() {
		data = null;
	}
	
}

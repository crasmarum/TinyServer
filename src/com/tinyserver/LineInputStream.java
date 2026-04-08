package com.tinyserver;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;

public class LineInputStream extends FilterInputStream {
	private byte[] mTail = new byte[2]; 
	private int mTailLen = 0;
	private byte[] mByteBuffer = null;

	public LineInputStream(InputStream in) {
		super(in);
	}

	public String readLine() throws IOException {
		byte[] bytes = readLineAsBytes();
		
		if (bytes == null) {
			return null;
		} else {
			return new String(bytes, 0, bytes.length, "UTF-8");
		}
	}
	
	public byte[] readEntireLineAsBytes() throws IOException {
		byte[] bytes = readLineAsBytes();
		if (mTailLen == 0) {
			return bytes;
		}
		byte[] ret = new byte[bytes.length + mTailLen];
		System.arraycopy(bytes, 0, ret, 0, bytes.length);
		System.arraycopy(mTail, 0, ret, bytes.length, mTailLen);
		
		return ret;
	}
	
	public byte[] readLineAsBytes() throws IOException {
		mTailLen = 0;
		InputStream wrapped = super.in;

		byte buff[] = mByteBuffer;
		if (buff == null) {
			buff = mByteBuffer = new byte[128];
		}
		int length = buff.length;
		int buffIndex = 0;
		int c;
		while ((c = wrapped.read()) != -1) {
			if (c == '\n') {
				mTail[mTailLen ++] = (byte) c;
				break;
			}
			if (c == '\r') {
				mTail[mTailLen ++] = (byte) c;
				int nextChar = wrapped.read();
				if (nextChar != '\n') {
					if (!(wrapped instanceof PushbackInputStream)) {
						wrapped = super.in = new PushbackInputStream(
								((InputStream) (wrapped)));
					}
					((PushbackInputStream) wrapped).unread(nextChar);
				} else {
					mTail[mTailLen ++] = (byte) nextChar;
				}
				break;
			}
			if (--length < 0) {
				buff = new byte[buffIndex + 128];
				length = buff.length - buffIndex - 1;
				System.arraycopy(mByteBuffer, 0, buff, 0, buffIndex);
				mByteBuffer = buff;
			}
			buff[buffIndex++] = (byte) c;
		}
		if (c == -1 && buffIndex == 0) {
			return null;
		} else {
			byte[] ret = new byte[buffIndex];
			System.arraycopy(buff, 0, ret, 0, buffIndex);
			return ret;
		}
	}
}

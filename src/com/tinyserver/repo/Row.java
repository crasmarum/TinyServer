package com.tinyserver.repo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;

public class Row {
	final String[] mValues;

	final static int END_ROW = Integer.MIN_VALUE;

	final static int END_FILE = -1;

	private static final int MAX_LEN = 8192;

	private int mPrimaryKey = 0;

	private int mFileOffset = -1;

	private int mIndexOffset = -1;

	private boolean mIsDeleted;

	public Row(String[] values) {
		mValues = values;
	}

	Row(InputStream is) throws IOException {
		ArrayList<String> al = new ArrayList<String>();

		int len = 0;
		while ((len = readIntFromInputStream(is)) != END_ROW && len != END_FILE) {
			if (len < 0 && (!isDeleted())) {
				setIsDeleted(true);
				len = -len;
			} else if (len < 0 || len > MAX_LEN) {
				throw new IOException("invalid data format");
			}

			byte[] sBytes = new byte[len];
			int read = 0;
			while ((read = is.read(sBytes)) >= 0 && (len -= read) > 0);
			al.add(new String(sBytes, "UTF-8"));
		}

		mValues = (String[]) al.toArray(new String[0]);
	}

	public Row(RandomAccessFile is) throws UnsupportedEncodingException, IOException {
		ArrayList<String> al = new ArrayList<String>();

		int len = 0;
		mFileOffset = (int) is.getFilePointer();
		while ((len = readIntFromInputStream(is)) != END_ROW && len != END_FILE) {
			if (len < 0 && (!isDeleted())) {
				setIsDeleted(true);
				len = -len;
			} else if (len < 0 || len > MAX_LEN) {
				throw new IOException("invalid data format");
			}

			byte[] sBytes = new byte[len];
			int read = 0;
			while ((read = is.read(sBytes)) >= 0 && (len -= read) > 0);
			al.add(new String(sBytes, "UTF-8"));
		}

		mValues = (String[]) al.toArray(new String[0]);
	}

	public static Row createFrom(RandomAccessFile is) throws IOException {
		Row ret = new Row(is);
		if (ret == null || ret.getValues() == null || ret.getValues().length == 0) {
			return null;
		}
		return ret;
	}

	public byte[] toByteArray() throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		for (int i = 0; i < mValues.length; i++) {
			byte[] sBytes = mValues[i].getBytes("UTF-8");
			baos.write(intToByteArray(sBytes.length), 0, 4);
			baos.write(sBytes, 0, sBytes.length);
		}
		baos.write(intToByteArray(END_ROW));
		return baos.toByteArray();
	}

	static byte[] intToByteArray(final int integer) {
		return new byte[] { (byte) (integer >>> 24), (byte) (integer >> 16 & 0xff), (byte) (integer >> 8 & 0xff),
				(byte) (integer & 0xff) };
	}

	static void writeIntToByteArray(final int integer, final byte[] byteArray, int offset) {
		byteArray[offset++] = (byte) (integer >>> 24);
		byteArray[offset++] = (byte) (integer >> 16 & 0xff);
		byteArray[offset++] = (byte) (integer >> 8 & 0xff);
		byteArray[offset++] = (byte) (integer & 0xff);
	}

	static int readIntFromByteArray(byte[] byteArray) {
		return readIntFromByteArray(byteArray, 0);
	}

	static int readIntFromByteArray(byte[] byteArray, int offset) {
		int value = 0;
		for (int i = 0; i < 4; i++) {
			int shift = 24 - i * 8;
			value += (byteArray[i + offset] & 0x000000FF) << shift;
		}
		return value;
	}

	static int readIntFromInputStream(InputStream is) throws IOException {
		int value = 0;
		int read = 0;
		for (int i = 0; i < 4; i++) {
			read = is.read();
			if (read < 0) {
				return END_FILE;
			}
			int shift = 24 - i * 8;
			value += (read & 0x000000FF) << shift;
		}
		return value;
	}

	static int readIntFromInputStream(RandomAccessFile is) throws IOException {
		int value = 0;
		int read = 0;
		for (int i = 0; i < 4; i++) {
			read = is.read();
			if (read < 0) {
				return END_FILE;
			}
			int shift = 24 - i * 8;
			value += (read & 0x000000FF) << shift;
		}
		return value;
	}

	public String[] getValues() {
		return mValues;
	}

	public final static void main(String[] args) throws IOException {
		Row row = new Row(new String[] { "1aaaaaaaa", "2aaaaaaa", "aaaaaaaaabc" });
		Row row2 = new Row(new ByteArrayInputStream(row.toByteArray()));

		System.out.println(Arrays.toString(row2.getValues()));
		System.out.println(readIntFromByteArray(intToByteArray(END_ROW)));
		System.out.println(readIntFromByteArray(intToByteArray(1)));
		System.out.println(readIntFromByteArray(intToByteArray(Integer.MIN_VALUE)));
		System.out.println(readIntFromByteArray(intToByteArray(Integer.MAX_VALUE)));
	}

	public int getPrimaryKey() {
		return mPrimaryKey;
	}

	int getFileOffset() {
		return mFileOffset;
	}

	void setFileOffset(int fileOffset) {
		mFileOffset = fileOffset;
	}

	int getIndexOffset() {
		return mIndexOffset;
	}

	void setIndexOffset(int indexOffset) {
		mIndexOffset = indexOffset;
	}

	public boolean isDeleted() {
		return mIsDeleted;
	}

	public void setIsDeleted(boolean isDeleted) {
		mIsDeleted = isDeleted;
	}

	public String toString() {
		return (isDeleted() ? "[DELETED] " : "")
				+ (mValues != null && mValues.length > 0 ? Arrays.toString(mValues) : "EMPTY");
	}
}

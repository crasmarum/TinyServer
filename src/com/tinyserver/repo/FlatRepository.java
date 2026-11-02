package com.tinyserver.repo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;

import com.tinyserver.servlet.ReadWriteLock;

public class FlatRepository {
	public static String REPOSITORY_PATH; 
	private static final String LOCAL_REPOSITORY = "morumottoRepository";
	static {
		String parent = System.getProperty("user.home");
		REPOSITORY_PATH = parent + File.separator + LOCAL_REPOSITORY;
	}
	private ReadWriteLock mLock = new ReadWriteLock();
	private File mStorage;
	private File mIndex;
	private byte[] mIndexData;
	
	public FlatRepository(String tableName) throws IOException {
		File base = getBase();
		mStorage = new File(base, tableName + ".d87");
		mIndex = new File(base, tableName + ".i87");
		if (! mStorage.exists())
			mStorage.createNewFile();
		if (! mIndex.exists())
			mIndex.createNewFile();
		readIndexData();
	}
	
	private void readIndexData() throws IOException {
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(mIndex);

			mIndexData = new byte[(int) mIndex.length()];
			int read = -1;
			int offset = 0;
			int len = mIndexData.length;
			while ((read = fis.read(mIndexData, offset, len)) != -1 && len > 0) {
				offset += read;
				len -= read;
			}		
		} finally {
			if (fis != null) {
				fis.close();
			}
		}
	}
	
	public Row[] searchPrimaryKey(String string) 
	throws InterruptedException, IOException {
		try {
			mLock.acquireRead();
			return doSearchForPrimaryKey(string);
		} finally {
			mLock.releaseRead();
		}
	}

	private Row[] doSearchForPrimaryKey(String string)
			throws IOException, FileNotFoundException {
		if (mIndexData.length == 0)
			return new Row[0];
		
		ArrayList<Row> ret = new ArrayList<Row>();
		int hash = getHashCode(string);
		int found = getInsertPosition(mIndexData, hash);
		
		if (found < 0) {
			return new Row[0];
		}
		
		ArrayList<Integer> pointers = new ArrayList<Integer>();
		ArrayList<Integer> indexes = new ArrayList<Integer>();
		
		int from = found - 8;
		while (from >= 0 
				&& hash == Row.readIntFromByteArray(mIndexData, from)) {
			pointers.add(Row.readIntFromByteArray(mIndexData, from + 4));
			indexes.add(from);
			from -= 8;
		}
		
		int to = found;
		while (to < mIndexData.length 
				&& hash == Row.readIntFromByteArray(mIndexData, to)) {
			pointers.add(Row.readIntFromByteArray(mIndexData, to + 4));
			indexes.add(to);
			to += 8;
		}
		
		RandomAccessFile fis = null;
		try {
			fis = new RandomAccessFile(mStorage, "r");
			for (int i = 0; i < pointers.size(); i ++) {
				fis.seek(((Integer)pointers.get(i)).intValue());
				Row row = Row.createFrom(fis); 
				
				if (string.equals(row.getValues()[row.getPrimaryKey()])) {
					row.setFileOffset(((Integer)pointers.get(i)).intValue());
					row.setIndexOffset(((Integer)indexes.get(i)).intValue());
					
					ret.add(row);
				}
			}
		} finally {
			if (fis != null) {
				fis.close();
			}
		}
		return (Row[]) ret.toArray(new Row[0]);
	}

	public void append(Row row) throws IOException, InterruptedException {
		try {
			mLock.acquireWrite();
			
			String pk = row.getValues()[row.getPrimaryKey()];
			if (doSearchForPrimaryKey(pk).length > 0) {
				throw new IOException("This field [" + pk + "] is not unique.");
			}
			
			doAppendRowToFile(row);
			addIndex(row.getValues()[row.getPrimaryKey()], row.getFileOffset());
			
			saveIndexToFile();
		} finally {
			mLock.releaseWrite();
		}
	}
	


	private void saveIndexToFile() throws FileNotFoundException, IOException {
		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(mIndex);
			fos.write(mIndexData);
		} finally {
			if (fos != null) {
				fos.close();
			}
		}
	}

	private void doAppendRowToFile(Row row) throws FileNotFoundException, IOException {
		FileOutputStream fos = null;
		row.setFileOffset((int) mStorage.length());
		try {
			fos = new FileOutputStream(mStorage, true);
			fos.write(row.toByteArray());
		} finally {
			if (fos != null) {
				fos.close();
			}
		}
	}
	
	public void update(Row row) throws IOException, InterruptedException {
		try {
			mLock.acquireWrite();
			
			String pk = row.getValues()[row.getPrimaryKey()];
			Row[] existingRow = doSearchForPrimaryKey(pk);
			
			if (existingRow.length == 0) {
				throw new IOException("The [" + pk + "] entry no longer exists.");
			}
			
			doAppendRowToFile(row);
			replaceIndexFor(existingRow[0], row);
			flagAsDeleted(existingRow[0]);
		} finally {
			mLock.releaseWrite();
		}
	}
	
	public void delete(Row row) throws IOException, InterruptedException {
		try {
			mLock.acquireWrite();
			
			String pk = row.getValues()[row.getPrimaryKey()];
			Row[] existingRow = doSearchForPrimaryKey(pk);
			
			if (existingRow.length == 0) {
				throw new IOException("The [" + pk + "] entry no longer exists.");
			}
			
			deleteIndexFor(existingRow[0]);
			flagAsDeleted(existingRow[0]);
		} finally {
			mLock.releaseWrite();
		}
	}
	
	private void flagAsDeleted(Row row) 
	throws IOException {
		RandomAccessFile fis = null;
		try {
			fis = new RandomAccessFile(mStorage, "rw");
			fis.seek(row.getFileOffset());
			int len = Row.readIntFromInputStream(fis);
			
			fis.seek(row.getFileOffset());
			fis.write(Row.intToByteArray(- len));
		} finally {
			if (fis != null) {
				fis.close();
			}
		}
	}

	void replaceIndexFor(Row existingRow, Row newRow) 
	throws FileNotFoundException, IOException {
		Row.writeIntToByteArray(
				newRow.getFileOffset(), 
				mIndexData, 
				existingRow.getIndexOffset() + 4);
		saveIndexToFile();
	}
	
	void deleteIndexFor(Row existingRow) 
	throws FileNotFoundException, IOException {
		byte[] newIndex = new byte[mIndexData.length - 8];
		System.arraycopy(mIndexData, 0, 
				newIndex, 0, existingRow.getIndexOffset());
		System.arraycopy(mIndexData, existingRow.getIndexOffset() + 8, 
					newIndex, existingRow.getIndexOffset(), 
					newIndex.length - existingRow.getIndexOffset());
		
		mIndexData = newIndex;
		saveIndexToFile();
	}

	private void addIndex(String string, int dbOffset) throws IOException {
		byte[] newData = addIndexToByteArray(string, mIndexData, dbOffset);
		mIndexData = newData;
	}

	private byte[] addIndexToByteArray(String string, byte[] byteArray, int dbOffset) throws IOException {
		int hash = getHashCode(string);
		int found = getInsertPosition(byteArray, hash);
		
		byte[] newData = new byte[byteArray.length + 8];
		System.arraycopy(byteArray, 0, newData, 0, found);
		
		Row.writeIntToByteArray(hash, newData, found);
		Row.writeIntToByteArray(dbOffset, newData, found + 4);
		
		if (byteArray.length > found) {
			System.arraycopy(byteArray, found, newData, found + 8, byteArray.length - found);
		}
		
		return newData;
	}
	
	private static int getInsertPosition(byte[] content, int hash) throws IOException {
		if (content.length == 0) {
			return 0;
		}
		
		int leftPosition = 0;
		int rightPosition = content.length - 8;
		
		int valueLeft = Row.readIntFromByteArray(content);
		int valueRight = Row.readIntFromByteArray(content, rightPosition);
		
		while (true) {
			if (hash <= valueLeft)
				return leftPosition;
			
			if (hash >= valueRight) 
				return rightPosition + 8;
			
			int middle = (rightPosition + leftPosition) / 2;
			middle = middle - (middle % 8);
			
			if (middle == leftPosition)
				return leftPosition + 8;
			
			int valueMiddle = Row.readIntFromByteArray(content, middle);
			
			if (hash < valueMiddle) {
				rightPosition = middle;
				valueRight = valueMiddle;
			} else {
				leftPosition = middle;
				valueLeft = valueMiddle;
			}
		}
	}

	public void append(String[] rowContent) 
	throws IOException, InterruptedException {
		Row row = new Row(rowContent);
		append(row);
	}
	
	public Row[] selectAll() 
	throws IOException, InterruptedException {
		ArrayList<Row> ret = new ArrayList<Row>();
		RandomAccessFile fis = null;
		try {
		    mLock.acquireRead();
			
			fis = new RandomAccessFile(mStorage, "r");		
			Row row = null;
			while((row = Row.createFrom(fis)) != null) {
				ret.add(row);
			}
		} finally {
			try {
				if (fis != null) {
					fis.close();
				}
			} catch (Exception ignored) {}
			mLock.releaseRead();
		}
		
		return (Row[]) ret.toArray(new Row[0]);
	}
	
	public void compact() 
	throws IOException, InterruptedException {
		File newDB = new File(mStorage.getAbsolutePath() + ".t87");
		
		try {
		    mLock.acquireWrite();
		    byte[] newIndex = copyUndeletedData(newDB);
			
			if (! mStorage.delete() || ! newDB.renameTo(mStorage)) {
				throw new IOException("failed to move [" + newDB 
						+ "] tp [" + mStorage + "] ");
			}
			mIndexData = newIndex;
			saveIndexToFile();
		} finally {		
			mLock.releaseWrite();
		}
	}

	private  byte[] copyUndeletedData(File newDB) throws FileNotFoundException, IOException {
		RandomAccessFile fis = null;;
		RandomAccessFile fos = null;
		byte[] newIndex = new byte[0];
		try {
			fis = new RandomAccessFile(mStorage, "r");
			fos = new RandomAccessFile(newDB, "rw");		
			
			Row row = null;
			while((row = Row.createFrom(fis)) != null) {
				if (row.isDeleted()) {
					continue;
				}
				
				int fPointer = (int) fos.getFilePointer();
				fos.write(row.toByteArray());
				newIndex = addIndexToByteArray(row.getValues()[row.getPrimaryKey()], 
						newIndex, fPointer);
			}
			
			fos.close();
			fos = null;
			fis.close();
			fis = null;
			
			return newIndex;
		} finally {
			try {
				if (fos != null) {
					fos.close();
				}
			} catch (Exception ignored) {}
			try {
				if (fis != null) {
					fis.close();
				}
			} catch (Exception ignored) {}
		}
	}
	
	public int[] readIndex() throws IOException {
		int[] ret = new int[(int) (mIndex.length() / 4)];
		int index = 0;
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(mIndex);
			for (int i = 0; i < ret.length; i++) {
				ret[index ++] = Row.readIntFromInputStream(fis);
			}
		} finally {
			if (fis != null) {
				fis.close();
			}
		}
		return ret;
	}
		
	private File getBase() {
		File base = new File(REPOSITORY_PATH);
		if (! base.exists()) {
			if (! base.mkdir()) {
				throw new RuntimeException("cannot create: " + base.getAbsolutePath());
			}
		}
		return base;
	}
	
	private int getHashCode(String string) {
		int hashCode = 0;
		char[] value = string.toCharArray();
	    for (int i = 0; i < value.length; i++) {
	      hashCode = hashCode * 31 + value[i];
	    }
	    return hashCode;
	}
	
	public final static void main(String[] args) throws IOException, InterruptedException {
		/*
		FlatRepository table = new FlatRepository("test");
		int count = 10000;
		
		if (false) {
			for (int i = 0; i < count; i++) {
				Row[] rows = table.searchPrimaryKey(i + "row aaaaaaaaaaaaaaa" + i);
				if (rows.length != 1)
					throw new IOException("" + i);
				table.delete(rows[0]);
			}
			table.compact();
			Row[] rows = table.selectAll();
			for (int i = 0; i < rows.length; i++) {
				System.out.println(rows[i]);
			}
			System.out.println("done.");
			return;
		}
		
		if (false) {
			for (int i = 0; i < count; i++) {
				Row[] rows = table.searchPrimaryKey(i + "row aaaaaaaaaaaaaaa" + i);
				if (rows.length != 1)
					throw new IOException("" + i);
				System.out.println(rows[0]);
			}
			return;
		}
		
		if (false) {
			int[] ttt = table.readIndex();
			
			for (int j = 2; j < ttt.length; j += 2) {
				if (ttt[j - 2] > ttt[j]) {
					System.out.println("err 1 " + j);
					System.out.flush();
					throw new IOException("" + j);
				}
			}
			
			System.out.println(Arrays.toString(ttt));
			return;
		}
			
	//*	
		long t = System.currentTimeMillis();
		for (int i = 0; i < count; i++) {
			//System.out.println(i);
			table.append(new String[] { i + "row aaaaaaaaaaaaaaa" + i, "data aaaaaaaa" + i, "other data aaaaaaaa" + i});
			//System.out.println(Arrays.toString(table.readIndex()));
			//System.out.flush();
			if (i % 100 == 99)
				System.out.println(i);
		}
		System.out.println(count + " inserts: " + (System.currentTimeMillis() - t) + "ms");
		
		
		
		for (int i = count - 1; i >= 0; i--) {
			Row[] rows = table.searchPrimaryKey(i + "row aaaaaaaaaaaaaaa" + i);
			if (rows.length != 1)
				throw new IOException("" + i);
			System.out.println(rows[0]);
			
			for (int j = 1; j < rows[0].getValues().length; j++) {
				rows[0].getValues()[j] = "U " + rows[0].getValues()[j];
			}
			
			table.update(rows[0]);
			
			rows = table.searchPrimaryKey(i + "row aaaaaaaaaaaaaaa" + i);
			System.out.println(rows[0]);
		}
		
		/*
		for (int i = 0; i < count; i++) {
			Row[] rows = table.searchPrimaryKey(i + "row aaaaaaaaaaaaaaa" + i);
			if (rows.length != 1)
				throw new IOException("" + i);
			System.out.println(i + "row aaaaaaaaaaaaaaa" + i);
			System.out.println(rows[0].getValues()[0]);
			if (! rows[0].getValues()[0].equals(i + "row aaaaaaaaaaaaaaa" + i)) {
				throw new IOException("uuu" + i);
			}
		}
		//*/
		
		/*
		long tt = System.currentTimeMillis();
		Row[] rows = table.searchPrimaryKey("row aaaaaaaaaaaaaaa5");
		for (int i = 0; i < rows.length; i++) {
			System.out.println(i + " " + Arrays.toString(rows[i].getValues()));
		}
		System.out.println("search: " + (System.currentTimeMillis() - tt) + "ms");
		//*/
		System.out.println("done.");
	}
}

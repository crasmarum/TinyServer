package com.tinyserver.servlet;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.tinyserver.servlet.JsonObj.JSON_Type;

public class JsonSerializer {
	private static final String OBJECT = "object";

	private static String escape(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : value.toCharArray())
            sb.append(switch (ch) {
                case '\\', '"', '/' -> "\\" + ch;
                case '\b' -> "\\b";
                case '\t' -> "\\t";
                case '\n' -> "\\n";
                case '\f' -> "\\f";
                case '\r' -> "\\r";
                default -> ch < ' ' ? String.format("\\u%04x", ch) : ch;
            });
        return sb.append('"').toString();
	}
	
	/**
	 * TO DO: write documentation.
	 */
	public static String toJson(Object obj) throws IllegalArgumentException, IllegalAccessException {
		return objToJson(obj);
	}
	
	protected static String objToJson(Object obj) throws IllegalArgumentException, IllegalAccessException {
		final StringBuilder sb = new StringBuilder();
		List<Field> allFields = Arrays.asList(obj.getClass().getDeclaredFields());
		sb.append("{");
		sb.append(escape(OBJECT)).append(":")
			.append("\"").append(obj.getClass().getSimpleName()).append("\"");
		
		for (Field fld : allFields) {
			sb.append(",");
			sb.append(escape(fld.getName())).append(":");
			
			fld.setAccessible(true);
			if (fld.getType().getName().equals(String.class.getName())) {
				if (fld.get(obj) == null) {
					sb.append("null");
				} else {
					sb.append(escape(fld.get(obj).toString()));
				}
			} else if (fld.getType().getName().equals(int.class.getName())) {
				sb.append(fld.get(obj).toString());
			} else if (fld.getType().getName().equals(long.class.getName())) {
				sb.append(fld.get(obj).toString());
			} else if (fld.getType().isAssignableFrom(List.class)) {		
				addList(fld, sb, obj);
			} else if (fld.getType().getName().equals(BigInteger.class.getName())) {
				if (fld.get(obj) == null) {
					sb.append("null");
				} else {
					BigInteger bi = (BigInteger)fld.get(obj);
					sb.append(escape(bi.toString()));
				}
			} else {
				throw new IllegalArgumentException("Not implemented for: " + fld.getType().getName());
			}
		}
		
		sb.append("}");
		return sb.toString();
	}
	
	private static void addList(Field fld, StringBuilder sb, Object obj)
			throws IllegalArgumentException, IllegalAccessException {
		ParameterizedType stringListType = (ParameterizedType) fld.getGenericType();
		Class<?> listItemType = (Class<?>) stringListType.getActualTypeArguments()[0];

		sb.append("[");
		if (listItemType.getName().equals(String.class.getName())) {
			@SuppressWarnings("unchecked")
			List<String> list = (List<String>) fld.get(obj);
			for (int var = 0; var < list.size(); var++) {
				sb.append(escape(list.get(var)));
				sb.append(var < list.size() - 1 ? "," : "");
			}
		} else if (listItemType.getName().equals(Integer.class.getName())) {
			@SuppressWarnings("unchecked")
			List<Integer> list = (List<Integer>) fld.get(obj);
			for (int var = 0; var < list.size(); var++) {
				sb.append(list.get(var));
				sb.append(var < list.size() - 1 ? "," : "");
			}
		} else if (listItemType.getName().equals(Long.class.getName())) {
			@SuppressWarnings("unchecked")
			List<Long> list = (List<Long>) fld.get(obj);
			for (int var = 0; var < list.size(); var++) {
				sb.append(list.get(var));
				sb.append(var < list.size() - 1 ? "," : "");
			}
		} else {
			@SuppressWarnings("unchecked")
			List<Object> list = (List<Object>) fld.get(obj);
			for (int var = 0; var < list.size(); var++) {
				sb.append(objToJson(list.get(var)));
				sb.append(var < list.size() - 1 ? "," : "");
			}
		}
			
		sb.append("]");

	}	
	
	public static boolean getObjFrom(String json, Object obj) {
		List<Field> allFields = Arrays.asList(obj.getClass().getDeclaredFields());
		final Map<String, Field> fields = new HashMap<>();
		for (Field fld : allFields) {
			fields.put(fld.getName(), fld);
		}
		boolean[] ret = new boolean[] {true};
		
		JsonParser parser = new JsonParser();
		JsonListener listener = new JsonListener() {
			
			@Override
			public void on_data(JSON_Type type, List<String> keys, String value) {
				
				if (keys.size() == 0) {
					throw new IllegalArgumentException("Empty keys");
				}
				
				String fldName = JsonListener.toString(keys);
				if (OBJECT.equals(fldName) && !obj.getClass().getSimpleName().equals(value)) {
					throw new IllegalArgumentException("Unexpected class: " + value);
				}
				if (OBJECT.equals(fldName)) {
					return;
				}
				
				if (fldName.contains(JsonListener.COLON)) {
					Field fldList = fields.get(fldName.substring(0, fldName.indexOf(JsonListener.COLON)));
					try {
						parseList(fldList, obj, fldName, value);
					} catch (Exception e) {
						throw new IllegalArgumentException(e);
					}
					return;
				}

				Field fld = fields.get(fldName);
				
				try {
					setSimpleFields(obj, value, fldName, fld);
				} catch (IllegalAccessException e) {
					ret[0] = false;
				}
			}
		};
		parser.add_listener(listener);
		parser.parse(json, new JsonObj());
		
		return ret[0];
	}
	
	private static void setSimpleFields(Object obj, String value, String elementFldName, Field foundFld)
			throws IllegalAccessException {
		if (foundFld == null) {
			throw new IllegalArgumentException("Unexpected list element field: " + elementFldName);
		}
		foundFld.setAccessible(true);
		if (foundFld.getType().getName().equals(String.class.getName())) {
			if ("null".equals(value)) {
				foundFld.set(obj, null);
			} else {
				foundFld.set(obj, value);
			}
		} else if (foundFld.getType().getName().equals(int.class.getName())) {
			foundFld.set(obj, Integer.parseInt(value));
		} else if (foundFld.getType().getName().equals(long.class.getName())) {
			foundFld.set(obj, Long.parseLong(value));
		} else if (foundFld.getType().getName().equals(BigInteger.class.getName())) {
			if ("null".equals(value)) {
				foundFld.set(obj, null);
			} else {
				foundFld.set(obj, new BigInteger(value));
			}
		} else {
			throw new IllegalArgumentException("Not implemented for: " + foundFld.getType().getName());
		}
	}
	
	static void setListFor(Class<?> clazz, Field fldList, Object obj) throws IllegalArgumentException, IllegalAccessException {
		fldList.setAccessible(true);
		
		if (fldList.getGenericType().toString().endsWith(
				"." + clazz.getSimpleName() + ">")) {
			fldList.set(obj, new ArrayList<>());
		} else {
			throw new IllegalArgumentException("Lists parsing not implemented for: " + fldList.getGenericType());
		}
	}
	
	static Object createListElementFor(Class<?> clazz, Field fldList) throws Exception {
		if (fldList.getGenericType().toString().endsWith(
				"." + clazz.getSimpleName() + ">")) {
			return clazz.getDeclaredConstructor().newInstance();
		} else {
			throw new IllegalArgumentException("Lists parsing not implemented for: " + fldList.getGenericType());
		}
	}

	@SuppressWarnings("unchecked")
	private static void parseList(Field fldList, Object obj, String fldName, String value)
			throws Exception {
		
		int firstIndx = fldName.indexOf(JsonListener.COLON);
		int nextIndx = fldName.indexOf(JsonListener.COLON, firstIndx + 1);
		String indx = fldName.substring(firstIndx + 1, nextIndx == -1 ? fldName.length() : nextIndx);
		String elementFldName = fldName.substring(fldName.lastIndexOf(JsonListener.COLON) + 1);	
		
		// System.out.println("DEBUG: " + fldList + " " + indx + " " + elementFldName + " " + value);
		if (!fldList.getType().isAssignableFrom(List.class)) {
			throw new IllegalArgumentException("Lists not supported properly yet: " + fldName);
		}
		
		if (indx.equals("0") && elementFldName.equals(OBJECT)) {
			setListFor(obj.getClass(), fldList, obj);
			return;
		} else if (elementFldName.equals(OBJECT)) {
			return;
		} else if (fldList.getGenericType().toString().endsWith(
				String.class.getSimpleName() + ">")) {
			fldList.setAccessible(true);
			if (indx.equals("0")) {
				fldList.set(obj, new ArrayList<String>());
			}
			((List<String>)fldList.get(obj)).add(value);
			return;
		} else if (fldList.getGenericType().toString().endsWith(
				Integer.class.getSimpleName() + ">")) {
			fldList.setAccessible(true);
			if (indx.equals("0")) {
				fldList.set(obj, new ArrayList<Integer>());
			}
			((List<Integer>)fldList.get(obj)).add(Integer.valueOf(value));
			return;
		} else if (fldList.getGenericType().toString().endsWith(
				Long.class.getSimpleName() + ">")) {
			fldList.setAccessible(true);
			if (indx.equals("0")) {
				fldList.set(obj, new ArrayList<Long>());
			}
			((List<Long>)fldList.get(obj)).add( Long.valueOf(value));
			return;
		}

		if (indx.equals("0") && elementFldName.equals(OBJECT)) {
			setListFor(obj.getClass(), fldList, obj);
			return;
		} else if (elementFldName.equals(OBJECT)) {
			return;
		}
		if (Integer.parseInt(indx) == ((List<Object>)fldList.get(obj)).size()) {
			((List<Object>)fldList.get(obj)).add(createListElementFor(obj.getClass(), fldList));
		}

		obj = ((List<Object>)fldList.get(obj)).get(((List<Object>)fldList.get(obj)).size() - 1);	
		List<Field> allFields = Arrays.asList(obj.getClass().getDeclaredFields());
		
		Field foundFld = null;
		for (Field fld : allFields) {
			if (fld.getName().equals(elementFldName)) {
				foundFld = fld;
			}
		}
		if (foundFld == null) {
			throw new IllegalArgumentException("No such field: " + fldName);
		}
		setSimpleFields(obj, value, elementFldName, foundFld);
	}

}

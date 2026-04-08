package com.tinyserver.servlet;

import java.util.Vector;

public class JsonObj {
	
	public enum JSON_Type {
		J_Object, J_Array, J_String, J_Number, J_True, J_False, J_Null
	};
	
	JSON_Type type_;
	JsonObj parent_;
	Vector<String> name_ = new Vector<String>();
	String value_ = "";
	
	public static char SEP = 0;

	public String getValue() {
		return value_;
	}

	public void setValue(String value_) {
		this.value_ = value_;
	}

	JsonObj(JSON_Type type, JsonObj parent) {
		type_ = type;
		parent_ = parent;
	}

	Vector<JsonObj> childrens_ = new Vector<>();

	public JsonObj() {
		type_ = JSON_Type.J_Object;
		parent_ = null;
	}

	String add_name(String name) {
		String indx = "";
		if (parent_ != null && parent_.type_ == JSON_Type.J_Array) {
			indx = SEP + "" + (parent_.childrens_.size() - 1);
		}
		
		String c_name = (parent_ != null && parent_.name_.size() > 0 ? parent_.name_.lastElement() : "")
				+ indx + SEP + name;
		
		name_.add(c_name);
		return name_.lastElement();
	}

	JSON_Type get_type() {
		return type_;
	}

	JsonObj get_parent() {
		return parent_;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		
		toString(this, sb);
		
		return sb.toString();
	}

	private void toString(JsonObj curr, StringBuilder sb) {
		if (curr == null) {
			return;
		}
		
		if (curr.getValue().length() > 0) {
			sb.append(curr.type_).append(" ")
				.append(curr.name_.firstElement().replace(SEP, ':'))
				.append(" -> ").append(curr.getValue());
			sb.append("\n");
		}
		
		for (JsonObj child : curr.childrens_) {
			toString(child, sb);
		}
	}
}
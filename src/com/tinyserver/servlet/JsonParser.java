package com.tinyserver.servlet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.Vector;

import com.tinyserver.servlet.JsonObj.JSON_Type;

public class JsonParser {
	
	static char[] is_ctrl = new char[]{ 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
			1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, };

	static char[] is_w_space = new char[]{ 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };

	static Boolean is_ending(char ch) {
		return ch == ']' || ch == '}' || ch == ',';
	}

	static Boolean is_number(char ch) {
		return ch >= 48 && ch <= 57;
	}

	static Boolean is_nz_number(char ch) {
		return ch > 48 && ch <= 57;
	}

	static Boolean is_starting_number(char ch) {
		return ch == '-' || is_number(ch);
	}

	static Boolean is_space(char ch) {
		// return ch == ' ' || ch == '\n' || ch == '\t' || ch == '\r';
		return ch < 256 && is_w_space[(int) ch] > 0;
	}

	static Boolean is_control(char ch) {
		// return ch == 127 || (ch >= 0 && ch < 32) || ch == JSON_QUOTE;
		return ch < 256 && is_ctrl[(int) ch] > 0;
	}


	static char JSON_QUOTE = '"';
	static char JSON_ESCAPE = '\\';

	String type_to_str[] = { "Object", "Array", "String", "Number", "True", "False", "Null" };

	public enum State {
		START, READ_ARRAY, READ_OBJ, READ_NAME, READ_COLON, READ_VALUE, READ_COMMA, READ_STRING, ERROR, STOP,
		READ_NUMBER,
	};

	static String state_to_str[] = { "START", "READ_ARRAY", "READ_OBJ", "READ_NAME", "READ_COLON", "READ_VALUE",
			"READ_COMMA", "READ_STRING", "ERROR", "STOP", "READ_NUMBER", };
	
	static void read_space(MySStream ss) {
		while (ss.peek() >= 0 && is_space((char)ss.peek())) {
			ss.get();
		}
	}
	
	static Boolean read_exponent(MySStream ss, Vector<Character> number) {
		if (ss.peek() == '-' || ss.peek() == '+') {
			number.add((char)ss.get());
		}
		if (!is_number((char)ss.peek())) {
			return false;
		}
		while (is_number((char)ss.peek())) {
			number.add((char)ss.get());
		}
		read_space(ss);
		return is_ending((char)ss.peek());
	}

	Boolean read_more_numbers(MySStream ss, Vector<Character> number) {
		while (is_number((char)ss.peek())) {
			number.add((char)ss.get());
		}
		if (ss.peek() == 'e' || ss.peek() == 'E') {
			number.add((char)ss.get());
			return read_exponent(ss, number);
		}

		read_space(ss);
		return is_ending((char)ss.peek());
	}

	Boolean read_fraction(MySStream ss, Vector<Character> number) {
		while (is_number((char)ss.peek())) {
			number.add((char)ss.get());
		}
		if (ss.peek() == '.') {
			number.add((char)ss.get());
			return read_more_numbers(ss, number);
		}
		if (ss.peek() == 'e' || ss.peek() == 'E') {
			number.add((char)ss.get());
			return read_exponent(ss, number);
		}
		read_space(ss);
		return is_ending((char)ss.peek());
	}

	Boolean read_number(MySStream ss, Vector<Character> number) {
		char ch = (char)ss.get();

		if (ch == '-' && ss.peek() == '0') {
			number.add(ch);
			number.add((char)ss.get());
			if (ss.peek() != '.') {
				return false;
			}
			number.add((char)ss.get());
			return read_more_numbers(ss, number);
		}

		if (ch == '-' && is_nz_number((char)ss.peek())) {
			number.add(ch);
			ch = (char)ss.get();
		}

		if (ch == '0' && ss.peek() == '.') {
			number.add(ch);
			number.add((char)ss.get());

			return read_more_numbers(ss, number);
		}

		if (ch == '0' && (ss.peek() == 'e' || ss.peek() == 'E')) {
			number.add(ch);
			number.add((char)ss.get());

			return read_exponent(ss, number);
		}
		if (ch == '0') {
			number.add(ch);
			read_space(ss);
			return is_ending((char)ss.peek());
		}

		if (is_nz_number(ch)) {

			number.add(ch);
			return read_fraction(ss, number);
		}

		return false;
	}

	String to_utf8(int cp) {
		try {
			return Character.toString(cp);
		} catch (IllegalArgumentException ex) {
			return "err";
		}
	}

	Boolean read_unicode(MySStream ss, ByteArrayOutputStream baos) {
		int val = 0;
		byte[] ch = new byte[4];
		for (int var = 0; var < 4; ++var) {
			ch[var] = ss.get();

			if (ch[var] >= 65 && ch[var] <= 70) {
				val += (10 + ch[var] - 65) << 4 * (3 - var);
				continue;
			}
			if (ch[var] >= 97 && ch[var] <= 102) {
				val += (10 + ch[var] - 97) << 4 * (3 - var);
				continue;
			}
			if (ch[var] >= 48 && ch[var] <= 57) {
				val += (ch[var] - 48) << 4 * (3 - var);
				continue;
			}
			return false;
		}

		String conv = to_utf8(val);
		if ("err".equals(conv)) {
			return false;
		}
		try {
			baos.write(to_utf8(val).getBytes());
		} catch (IOException e) {
			return false;
		}
		return true;
	}

	Boolean read_escapedChar(MySStream ss, ByteArrayOutputStream baos) {
		switch (ss.get()) {
			case '"':
				baos.write('"');
				return true;
			case '\\':
				baos.write('\\');
				return true;
			case '/':
				baos.write('/');
				return true;
			case 'b':
				baos.write('\b');
				return true;
			case 'f':
				baos.write('\f');
				return true;
			case 'n':
				baos.write('\n');
				return true;
			case 'r':
				baos.write('\r');
				return true;
			case 't':
				baos.write('\t');
				return true;
			case 'u':
				return read_unicode(ss, baos);
			default:
				return false;
		}
	}

	Boolean read_string(MySStream ss, StringBuffer str) {
		if (ss.peek() != JSON_QUOTE) {
			return false;
		}
		ss.get();
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		while (ss.peek() != '"') {
			if (is_control((char)ss.peek())) {
				return false;
			}

			if (ss.peek() == JSON_ESCAPE) {
				ss.get();
				if (!read_escapedChar(ss, baos)) {
					return false;
				}
				continue;
			}
			baos.write(ss.get());
		}
		str.append(baos.toString(Charset.forName("utf-8")));

		return true;
	}

	String to_str(Vector<Character> vec) {
		if (vec.size() == 0) return "";
		
		StringBuilder ret = new StringBuilder();
		for (Character c : vec) {
		    ret.append(c);
		}
		return ret.toString();
	}

	static void remove_space(MySStream ss) {
		while (is_space((char)ss.peek())) {
			ss.get();
		}
	}

	Boolean read_bool(MySStream ss, Vector<Character> value) {
		String v_true = "true";
		String v_false = "false";
		String v_null = "null";
		String target;

		if (ss.peek() == 't') {
			target = v_true;
		} else if (ss.peek() == 'f') {
			target = v_false;
		} else if (ss.peek() == 'n') {
			target = v_null;
		} else {
			return false;
		}

		for (int var = 0; var < target.length(); ++var) {
			if (ss.get() != target.charAt(var)) {
				return false;
			}
			value.add(target.charAt(var));
		}

		return true;
	}
	
	JSON_Type get_token_type(String type) {
		if (type.equals("null")) return JSON_Type.J_Null;
		if (type.equals("true")) return JSON_Type.J_True;
		return JSON_Type.J_False;
	}

	String get_token_name(JSON_Type type) {
		return type == JSON_Type.J_Null ? "null" : "bool";
	}


	Boolean DEBUG = false;

	void L(String msg) {
		if (DEBUG)
			System.out.println(msg);
	}

	void L(String msg, String msg2) {
		if (DEBUG)
			System.out.println(msg + " " + msg2);
	}
	
	Map<String, String> key_value_ = new HashMap<>();
	Vector<String> keys_ = new Vector<>();
	Vector<JsonListener> listeners_ = new Vector<>(); // nor owned
	Stack<State> stack_ = new Stack<State>();
	State state_ = State.START;
	JsonObj current_ = new JsonObj();
	char last_open = 0;

	void pushState(State state) {
		stack_.add(state);
	}

	State popState() {
		if (stack_.size() == 0)
			return State.ERROR;
		State ret = stack_.lastElement();
		stack_.pop();
		return ret;
	}

	void print_state(MySStream ss) {
		char ch = (char)ss.peek();
		System.out.print("State: " + state_to_str[state_.ordinal()]
					+ " next char:\t" + (int)ss.peek() + "\t[ " + ch
		           + " ]\tStack: ");
		for (int var = 0; var < stack_.size(); ++var) {
			System.out.print(state_to_str[stack_.get(var).ordinal()] + ", ");
		}
		System.out.println();
	}

	void add_value(JSON_Type type) {
		JsonObj obj = new JsonObj(type, current_);
		current_.childrens_.add(obj);
	}

	void add_value(JSON_Type type, String value) {
		JsonObj obj = new JsonObj(type, current_);
		current_.childrens_.add(obj);
		String key = obj.add_name("");
		obj.setValue(value);

		if (!value.isEmpty()) {
			String[] ss = key.split("" + JsonObj.SEP);
			List<String> list = new ArrayList<>();
			for (String str : ss) {
				if (!str.isEmpty()) {
					list.add(str);
				}
			}
			
			for (JsonListener listener : listeners_) {
				listener.on_data(type, list, value);
			}
		}

		//key_value_[key] = value;
		//keys_.push_back(key);
	}
	
	Boolean bracket_mismatch(char bracket) {
		if ((last_open == '{' && bracket == ']')
				|| (last_open == '[' && bracket == '}') ) {
			return true;
		}
		last_open = 0;
		return false;
	}
	
	Map<String, String> get_data() {
		return key_value_;
	}
	
	void print() {
		for (String k_v : keys_) {
			System.out.println(k_v + " : " + key_value_.get(k_v));
		}
	}

	public void set_state(State state) {
		state_ = state;
	}

	public State get_state() {
		return state_;
	}
	
	void read_array(MySStream ss) {
		assert(state_ == State.READ_ARRAY);
		remove_space(ss);

		if (ss.peek() == ']') {
			if (bracket_mismatch((char)ss.peek())) {
				state_ = State.ERROR;
				return;
			}
			ss.get();
			L("END ARRAY");
			current_ = current_.parent_;
			state_ = popState();
			return;
		}
		remove_space(ss);
		if (ss.peek() == ',') {
			state_ = State.ERROR;
			return;
		}

		pushState(state_);
		pushState(State.READ_COMMA);
		state_ = State.READ_VALUE;
		return;
	}
	
	void read_obj(MySStream ss) {
		assert(state_ == State.READ_OBJ);
		remove_space(ss);

		if (ss.peek() == '}') {
			if (bracket_mismatch((char)ss.peek())) {
				state_ = State.ERROR;
				return;
			}
			ss.get();
			L("END OBJ");
			current_ = current_.parent_;
			state_ = popState();
			return;
		}

		L("Reading key");
		StringBuffer str = new StringBuffer();
		if (!read_string(ss, str)) {
			state_ = State.ERROR;
			return;
		}
		ss.get(); // read \"
		L("OBJ key: ", str.toString());
		current_.add_name(str.toString());

		remove_space(ss);
		if (ss.peek() != ':') {
			state_ = State.ERROR;
			return;
		}
		ss.get();
		remove_space(ss);
		if (ss.peek() == ',') {
			state_ = State.ERROR;
			return;
		}

		L("Reading OBJ value... ");
		pushState(state_);
		pushState(State.READ_COMMA);
		state_ = State.READ_VALUE;
		return;
	}
	
	public void read_value(MySStream ss, Vector<Character> acc) {
		assert(state_ == State.READ_VALUE);
		remove_space(ss);
		acc.clear();

		if (ss.peek() == '-' || is_number((char)ss.peek())) {
			pushState(State.READ_VALUE);
			state_ = State.READ_NUMBER;
			if (!read_number(ss, acc)) {
				state_ = State.ERROR;
				return;
			}
			remove_space(ss);
			L("Number: ", to_str(acc));
			add_value(JSON_Type.J_Number, to_str(acc));

			state_ = popState();
			return;
		}

		if (ss.peek() == '"') {
			pushState(State.READ_VALUE);
			state_ = State.READ_STRING;
			StringBuffer str = new StringBuffer();
			if (!read_string(ss, str)) {
				state_ = State.ERROR;
				return;
			}
			
			for (char ch : str.toString().toCharArray()) {
				acc.add(ch);
			}


			ss.get();
			remove_space(ss);
			L("String: ", to_str(acc));
			add_value(JSON_Type.J_String, to_str(acc));

			state_ = popState();
			return;
		}

		if (ss.peek() == 't' || ss.peek() == 'f' || ss.peek() == 'n') {

			pushState(State.READ_VALUE);
			state_ = State.READ_NUMBER;
			if (!read_bool(ss, acc)) {
				state_ = State.ERROR;
				return;
			}

			remove_space(ss);
			L("Token: ", to_str(acc));
			add_value(get_token_type(to_str(acc)), to_str(acc));

			state_ = popState();
			return;
		}

		if (ss.peek() == '[') {
			ss.get();
			last_open = '[';
			pushState(State.READ_VALUE);
			state_ = State.READ_ARRAY;
			L("START ARRAY");
			add_value(JSON_Type.J_Array, "");
			current_ = current_.childrens_.lastElement();
			return;
		}

		if (ss.peek() == '{') {
			ss.get();
			last_open = '{';
			pushState(State.READ_VALUE);
			state_ = State.READ_OBJ;
			L("START OBJ");
			add_value(JSON_Type.J_Object, "");
			current_ = current_.childrens_.lastElement();
			return;
		}

		if (ss.peek() == ']') {
			if (bracket_mismatch((char)ss.peek())) {
				state_ = State.ERROR;
				return;
			}
			state_ = popState();
			return;
		}

		if (ss.peek() == '}') {
			if (bracket_mismatch((char)ss.peek())) {
				state_ = State.ERROR;
				return;
			}
			state_ = popState();
			return;
		}

		if (ss.peek() == ',') {
			state_ = popState();
			return;
		}

		state_ = State.ERROR;
	}


	public Boolean parse(String ss, JsonObj root) {
		return parse(new MySStream(ss), root);
	}

	Boolean parse(MySStream ss, JsonObj root) {
		current_ = root;
		state_ = State.START;
		stack_.clear();

		while (state_ != State.STOP && state_ != State.ERROR) {
			if (DEBUG) print_state(ss);

			if (state_ == State.START && ss.peek() == '{') {
				ss.get();
				last_open = '{';
				pushState(State.STOP);
				state_ = State.READ_OBJ;
				continue;
			}
			if (state_ == State.START && ss.peek() == '[') {
				ss.get();
				last_open = '[';
				pushState(State.STOP);
				state_ = State.READ_ARRAY;
				continue;
			}
			if (state_ == State.START) {
				state_ = State.ERROR;
				continue;
			}

			if (state_ == State.READ_OBJ) {
				read_obj(ss);
				continue;
			}

			if (state_ == State.READ_VALUE) {
				Vector<Character> acc = new Vector<>();
				read_value(ss, acc);
				continue;
			}

			if (state_ == State.READ_ARRAY) {
				read_array(ss);
				continue;
			}

			if (state_ == State.READ_COMMA) {
				remove_space(ss);
				if (ss.peek() == ',') {
					ss.get();
					remove_space(ss);
					if (ss.peek() == ',' || ss.peek() == ']' || ss.peek() == '}') {
						state_ = State.ERROR;
						continue;
					}

					state_ = popState();
					continue;
				}
				if (ss.peek() == ']' || ss.peek() == '}') {
					state_ = popState();
					continue;
				}
				state_ = State.ERROR;
				continue;
			}
		}
		remove_space(ss);
		if (ss.peek() >= 0) {
			state_ = State.ERROR;
		}

		return state_ == State.STOP;
	}
	
	public 	void add_listener(JsonListener listener) {
		listeners_.add(listener);
	}

	public static String toString(Vector<Character> vec) {
		StringBuilder strB = new StringBuilder();
		if (vec == null) {
			return "NULL";
		}
		for (char ch : vec) {
			strB.append(ch);
		}
		return strB.toString();
	}
}


module example.web {
	requires transitive com.tinyserver;
	requires transitive java.net.http;
	requires transitive java.sql;
	
	exports com.example.web;
}
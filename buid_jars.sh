#!/bin/sh
cd src
javac -sourcepath . -d ../bin com/tinyserver/*.java com/tinyserver/servlet/*.java
cd ../bin
jar cf server.jar .
mv server.jar ../
cd ../ExampleWebApp/src
javac --module-path ../../server.jar:.  -sourcepath . -d ../bin com/example/web/*.java
cd ../bin
jar cf web.jar .
mv web.jar ../../


all:
	mkdir -p bin
	mkdir -p data
	javac -d bin src/common/*.java src/client/*.java src/server/*.java
	javac -d bin -cp bin test/*.java

run-server:
	java -cp bin server.Server

run-cli:
	java -cp bin client.UI

run-feat-test:
	java -cp bin test.FeatTest

run-stress-test:
	java -cp bin test.StressTest

docs:
	mkdir -p docs
	javadoc -d docs -cp bin src/common/.java src/client/.java src/server/*.java

clean:
	rm -rf bin data docs

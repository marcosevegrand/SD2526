all:
	mkdir -p bin
	mkdir -p data
# Compilação do código fonte principal
	javac -d bin src/common/*.java src/client/*.java src/server/*.java
# Compilação dos testes, incluindo a bin no classpath para encontrar a ClientLib
	javac -d bin -cp bin test/*.java

run-server:
	java -cp bin server.Server

run-client:
	java -cp bin client.UI

run-test:
	java -cp bin test.StressTest

docs:
	mkdir -p docs
	javadoc -d docs -cp bin src/common/.java src/client/.java src/server/*.java

clean:
	rm -rf bin data docs

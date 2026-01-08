# Default parameters
PORT ?= 12345
S ?= 10
D ?= 365
THREADS ?= 100
HOST ?= localhost
CLIENT_PORT ?= 12345

.PHONY: all run-server run-cli run-feat-test run-stress-test docs clean

# Default target: compile everything
all:
	mkdir -p bin
	mkdir -p data
	javac -d bin src/common/*.java src/client/*.java src/server/*.java
	javac -d bin -cp bin test/*.java
	@echo "Build complete!"
	@echo "To run the server: make run-server [PORT=port] [S=series] [D=days]"
	@echo "To run the client: make run-cli [HOST=host] [PORT=port]"

# Run server with configurable parameters
# Usage:
#   make run-server                                    # Uses defaults
#   make run-server PORT=8080 S=50 D=730 THREADS=200  # All custom
run-server:
	@echo "Starting server: PORT=$(PORT), S=$(S), D=$(D), THREADS=$(THREADS)"
	java -cp bin server.Server $(PORT) $(S) $(D) $(THREADS)

# Run client with configurable parameters
# Usage:
#   make run-cli                             # Uses defaults: localhost:12345
#   make run-cli HOST=example.com            # Custom host
#   make run-cli HOST=example.com PORT=8080  # Custom host and port
run-cli:
	@echo "Connecting to server at: $(HOST):$(CLIENT_PORT)"
	java -cp bin client.UI $(HOST) $(CLIENT_PORT)

# Run stress tests
run-stress-test:
	@echo "Running stress tests..."
	java -cp bin test.StressTest

run-robust-test:
	@echo "Running robust tests..."
	java -cp bin test.RobustTest

run-cache-test:
	@echo "Running cache tests (use S=3 D=10)..."
	java -cp bin test.CacheTest

# Generate Javadoc documentation
docs:
	mkdir -p docs
	javadoc -d docs -cp bin src/common/*.java src/client/*.java src/server/*.java
	@echo "Documentation generated in docs/"

# Clean build artifacts
clean:
	rm -rf bin data docs
	@echo "Clean complete"

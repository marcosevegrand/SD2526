# SD2526 Distributed Systems - Makefile
# This Makefile supports building and running the project with configurable parameters

# Default parameters - can be overridden from command line
PORT ?= 12345
S ?= 10
D ?= 365
HOST ?= localhost
CLIENT_PORT ?= 12345

.PHONY: all run-server run-cli run-feat-test run-stress-test docs clean help

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
#   make run-server                          # Uses defaults: port=12345, S=10, D=365
#   make run-server PORT=8080                # Custom port
#   make run-server PORT=8080 S=50           # Custom port and series
#   make run-server PORT=8080 S=50 D=730     # All custom parameters
run-server:
	@echo "Starting server with parameters: PORT=$(PORT), S=$(S), D=$(D)"
	java -cp bin server.Server $(PORT) $(S) $(D)

# Run client with configurable parameters
# Usage:
#   make run-cli                             # Uses defaults: localhost:12345
#   make run-cli HOST=example.com            # Custom host
#   make run-cli HOST=example.com PORT=8080  # Custom host and port
run-cli:
	@echo "Connecting to server at: $(HOST):$(CLIENT_PORT)"
	java -cp bin client.UI $(HOST) $(CLIENT_PORT)

# Run feature tests (no parameters)
run-feat-test:
	@echo "Running feature tests..."
	java -cp bin test.FeatTest

# Run stress tests (no parameters)
run-stress-test:
	@echo "Running stress tests..."
	java -cp bin test.StressTest

# Generate Javadoc documentation
docs:
	mkdir -p docs
	javadoc -d docs -cp bin src/common/*.java src/client/*.java src/server/*.java
	@echo "Documentation generated in docs/"

# Clean build artifacts
clean:
	rm -rf bin data docs
	@echo "Clean complete"

# Help target - displays usage information
help:
	@echo "SD2526 Distributed Systems - Build and Run Guide"
	@echo ""
	@echo "Building:"
	@echo "  make              Compile all sources"
	@echo "  make clean        Remove build artifacts"
	@echo ""
	@echo "Running Server:"
	@echo "  make run-server                      # Default: localhost:12345, S=10, D=365"
	@echo "  make run-server PORT=8080           # Custom port"
	@echo "  make run-server PORT=8080 S=50      # Custom port and memory"
	@echo "  make run-server PORT=8080 S=50 D=730  # All custom (2 years)"
	@echo ""
	@echo "Running Client:"
	@echo "  make run-cli                        # Connect to localhost:12345"
	@echo "  make run-cli HOST=example.com       # Connect to remote server"
	@echo "  make run-cli HOST=example.com PORT=8080  # Remote server custom port"
	@echo ""
	@echo "Testing:"
	@echo "  make run-feat-test                  # Run feature tests"
	@echo "  make run-stress-test                # Run stress tests"
	@echo ""
	@echo "Documentation:"
	@echo "  make docs                           # Generate Javadoc"
	@echo ""
	@echo "Examples:"
	@echo "  # Terminal 1: Run server with custom config"
	@echo "  make run-server PORT=8080 S=50 D=730"
	@echo ""
	@echo "  # Terminal 2-4: Connect multiple clients"
	@echo "  make run-cli HOST=localhost PORT=8080"
	@echo "  make run-cli HOST=localhost PORT=8080"
	@echo "  make run-cli HOST=localhost PORT=8080"
	@echo ""
	@echo "  # Run multiple server instances"
	@echo "  make run-server PORT=8080 S=50 D=365"
	@echo "  make run-server PORT=8081 S=50 D=365"
	@echo "  make run-server PORT=8082 S=50 D=365"

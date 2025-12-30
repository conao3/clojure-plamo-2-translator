# plamo-2-translator

A lightweight Clojure CLI tool for English to Japanese translation powered by PLaMo-2.

## Overview

This tool reads English text from stdin and outputs Japanese translation to stdout using streaming. It connects to a local PLaMo-2 server and produces natural Japanese in polite form (desu/masu style).

## Requirements

- Java 11+
- Clojure CLI tools
- A running PLaMo-2 compatible server

## Installation

Clone the repository and build the standalone JAR:

```bash
git clone https://github.com/conao3/clojure-plamo-2-translator.git
cd clojure-plamo-2-translator
clojure -T:build uber
```

## Usage

Basic usage with a local server:

```bash
echo "Hello, world!" | java -jar target/plamo-2-translator-standalone.jar http://localhost:8080
```

Or run directly with Clojure:

```bash
echo "Hello, world!" | clojure -M -m plamo-2-translator.plamo-2-translator http://localhost:8080
```

The tool accepts input from stdin and streams the translated output in real-time.

## How It Works

1. Reads English text from standard input
2. Sends the text to a PLaMo-2 server via the OpenAI-compatible API
3. Streams the Japanese translation back to stdout as it's generated

## Development

Run tests and build:

```bash
# Build JAR
clojure -T:build jar

# Build standalone uberjar
clojure -T:build uber
```

## License

Apache-2.0

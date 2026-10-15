CXX ?= g++
CXXFLAGS ?= -std=c++20 -O2 -Wall -Wextra -Wpedantic -pthread

.PHONY: all test clean
all: myct-core

myct-core: main.cpp
	$(CXX) $(CXXFLAGS) $< -o $@

test: myct-core
	python3 -m unittest discover -s tests -v

clean:
	rm -f myct-core

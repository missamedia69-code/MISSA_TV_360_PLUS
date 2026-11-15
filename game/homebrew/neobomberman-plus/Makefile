CC ?= cc
CFLAGS ?= -std=c99 -O2 -Wall -Wextra -Werror -pedantic
BUILD := build

.PHONY: test native clean

test: $(BUILD)/test_bomber
	./$(BUILD)/test_bomber
	python3 -m unittest discover -s tests -p 'test_*.py' -v

$(BUILD)/test_bomber: bomber.c bomber.h tests/test_bomber.c
	mkdir -p $(BUILD)
	$(CC) $(CFLAGS) bomber.c tests/test_bomber.c -o $@

native:
	./tools/build_native.sh

clean:
	rm -rf $(BUILD)

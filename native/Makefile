# Builds the ClosureJVM JVMTI native agent.
#
#   make            # builds ../build/native/libclosurejvmti.so
#   make clean
#
# JAVA_HOME must point at a JDK (for jvmti.h / jni.h). Falls back to deriving it
# from `java` on PATH if unset.

JAVA_HOME ?= $(shell dirname $(shell dirname $(shell readlink -f $(shell which java))))
OS        := $(shell uname -s)

OUT_DIR := ../build/native
ifeq ($(OS),Darwin)
  LIB := $(OUT_DIR)/libclosurejvmti.dylib
  MD  := darwin
else
  LIB := $(OUT_DIR)/libclosurejvmti.so
  MD  := linux
endif

CFLAGS := -O2 -fPIC -shared -Wall \
          -I"$(JAVA_HOME)/include" -I"$(JAVA_HOME)/include/$(MD)"

.PHONY: all clean
all: $(LIB)

$(LIB): closurejvmti.c
	@mkdir -p $(OUT_DIR)
	$(CC) $(CFLAGS) closurejvmti.c -o $(LIB)
	@echo "Built $(LIB)"

clean:
	rm -f $(OUT_DIR)/libclosurejvmti.so $(OUT_DIR)/libclosurejvmti.dylib

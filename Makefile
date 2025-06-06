all:

UNAME_OS := $(shell uname -s)
NAME := plamo-2-translator

.PHONY: all
all: native

define REPL_DEPS
{:deps
  {nrepl/nrepl {:mvn/version "RELEASE"}
   cider/cider-nrepl {:mvn/version "RELEASE"}}}
endef
export REPL_DEPS

define REPL_MIDDLEWARE
cider.nrepl/cider-middleware,
endef
export REPL_MIDDLEWARE

.PHONY: repl
repl:
	clj -A:dev -Sdeps "$${REPL_DEPS}" -M -m nrepl.cmdline --interactive --middleware "[$${REPL_MIDDLEWARE}]"

.PHONY: update
update:
	clojure -Sdeps '{:deps {com.github.liquidz/antq {:mvn/version "RELEASE"}}}' -M -m antq.core --upgrade --force

.PHONY: clean
clean:
	rm -rf target

.PHONY: jar
jar: target/$(NAME).jar

target/$(NAME).jar:
	clojure -T:build jar

.PHONY: deploy
deploy:
	clojure -T:build deploy

.PHONY: uber
uber: target/$(NAME)-standalone.jar

target/$(NAME)-standalone.jar:
	clojure -T:build uber

.PHONY: native
native: target/$(NAME)

ifeq ($(UNAME_OS),Darwin)
GRAAL_BUILD_ARGS += -H:+UnlockExperimentalVMOptions
GRAAL_BUILD_ARGS += -H:-CheckToolchain
endif

target/$(NAME): target/$(NAME)-standalone.jar
	native-image -jar $< \
	--features=clj_easy.graal_build_time.InitClojureClasses \
	--verbose \
	--no-fallback \
	$(GRAAL_BUILD_ARGS) \
	$@

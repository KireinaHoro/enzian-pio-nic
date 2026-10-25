.DEFAULT_GOAL := all
PKG_CONFIG ?= pkg-config
PREFIX ?= /usr/local
CORE_HEADERS ?= ../../core
CFLAGS ?= -O2 -g
TCP_CONNECTIONS ?= 64
TCP_BUFFER ?= 4096
TCP_HOLD_MS ?= 200
TCP_GATE_MS ?= 5000
TCP_STATE_US ?= 1000
TCP_SOCKET_LIMIT ?= 1024
DEADLINE_ARM_MS ?= 100
DEADLINE_TIMEOUT_MS ?= 5000
TCP_FLAGS = $(shell $(PKG_CONFIG) --cflags lauberhorn-tcp-runtime)
TCP_LIBS = $(shell $(PKG_CONFIG) --libs lauberhorn-tcp-runtime)
RT_FLAGS = $(shell $(PKG_CONFIG) --cflags lauberhorn)
RT_LIBS = $(shell $(PKG_CONFIG) --libs lauberhorn)
PROGRAMS = tcp-server tcp-transport deadline-validation
.PHONY: all install clean
all: $(PROGRAMS)
tcp-server: ../vm/tcp.c
	$(CC) $(CPPFLAGS) $(CFLAGS) -pthread -I$(CORE_HEADERS) $(TCP_FLAGS) \
	  -DTEST_TCP_GATE_MS=$(TCP_GATE_MS) -DTEST_TCP_HOLD_MS=$(TCP_HOLD_MS) -DTEST_TCP_STATE_US=$(TCP_STATE_US) \
	  -DTEST_TCP_SOCKET_LIMIT=$(TCP_SOCKET_LIMIT) -DTEST_TCP_CONNECTIONS=$(TCP_CONNECTIONS) -DTEST_TCP_BUFFER=$(TCP_BUFFER) \
	  $< $(LDFLAGS) $(TCP_LIBS) -o $@
tcp-transport: ../vm/tcp_transport.c
	$(CC) $(CPPFLAGS) $(CFLAGS) -pthread $(RT_FLAGS) $< $(LDFLAGS) $(RT_LIBS) -o $@
deadline-validation: deadline.c
	$(CC) $(CPPFLAGS) $(CFLAGS) -pthread $(RT_FLAGS) \
	  -DTEST_DEADLINE_ARM_MS=$(DEADLINE_ARM_MS) -DTEST_DEADLINE_TIMEOUT_MS=$(DEADLINE_TIMEOUT_MS) \
	  $< $(LDFLAGS) $(RT_LIBS) -o $@
install: all
	install -d $(DESTDIR)$(PREFIX)/bin $(DESTDIR)$(PREFIX)/share/lauberhorn-transport
	install -m755 $(PROGRAMS) $(DESTDIR)$(PREFIX)/bin/
	install -m644 memcached_bursts.py ../vm/tcp.py ../vm/tcp_reorder.py ../vm/tcp_scheduling.py ../vm/memcached.py ../vm/memcached_tcp.py ../vm/memcached_colocation.py $(DESTDIR)$(PREFIX)/share/lauberhorn-transport/
clean:
	rm -f $(PROGRAMS)

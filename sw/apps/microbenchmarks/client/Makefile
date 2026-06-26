CLIENT = bench_client

SOURCES.x = bench.x
SOURCES_CLNT.c = bench_client.c
SOURCES_CLNT.h =
TARGETS_CLNT.c = bench_xdr.c
TARGETS_CLNT.h = bench.h
OBJECTS_CLNT = $(SOURCES_CLNT.c:%.c=%.o) $(TARGETS_CLNT.c:%.c=%.o)

CFLAGS += -O2 -g -Wall -Wextra -I/usr/include/tirpc
LDLIBS += -ltirpc -lnsl

all: $(CLIENT)
.PHONY: all

$(TARGETS_CLNT.c): $(SOURCES.x)
	rpcgen -c $^ -o bench_xdr.c

$(TARGETS_CLNT.h): $(SOURCES.x)
	rpcgen -h $^ -o bench.h

$(OBJECTS_CLNT): $(SOURCES_CLNT.c) $(SOURCES_CLNT.h) $(TARGETS_CLNT.c) $(TARGETS_CLNT.h)

$(CLIENT): $(OBJECTS_CLNT)
	$(CC) -o $@ $^ $(LDLIBS)

clean:
	rm -f core $(TARGETS_CLNT.c) $(TARGETS_CLNT.h) $(OBJECTS_CLNT) $(CLIENT)
.PHONY: clean

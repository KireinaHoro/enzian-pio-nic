CLIENT = add_client

SOURCES.x = add.x
SOURCES_CLNT.c = add_client.c
SOURCES_CLNT.h =
TARGETS_CLNT.c = add_clnt.c add_xdr.c
TARGETS_CLNT.h = add.h
OBJECTS_CLNT = $(SOURCES_CLNT.c:%.c=%.o) $(TARGETS_CLNT.c:%.c=%.o)

CFLAGS += -g -I/usr/include/tirpc
LDLIBS += -ltirpc -lnsl

all: $(CLIENT)
.PHONY: all

$(TARGETS_CLNT.c): $(SOURCES.x)
	rpcgen -l $^ -o add_clnt.c
	rpcgen -c $^ -o add_xdr.c

$(TARGETS_CLNT.h): $(SOURCES.x)
	rpcgen -h $^ -o add.h

$(OBJECTS_CLNT): $(SOURCES_CLNT.c) $(SOURCES_CLNT.h) $(TARGETS_CLNT.c) $(TARGETS_CLNT.h)

$(CLIENT): $(OBJECTS_CLNT)
	$(CC) -o $@ $^ $(LDLIBS)

clean:
	rm -f core $(TARGETS_CLNT.c) $(TARGETS_CLNT.h) $(OBJECTS_CLNT) $(CLIENT)
.PHONY: clean

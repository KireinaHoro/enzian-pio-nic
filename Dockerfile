FROM ubuntu:22.04

RUN apt-get update
RUN apt-get -y install build-essential autoconf flex bison help2man openjdk-18-jdk curl git libz-dev
RUN mkdir /mill
RUN curl -L https://raw.githubusercontent.com/lefou/millw/0.4.10/millw > /mill/mill
RUN chmod +x /mill/mill

RUN git clone https://github.com/verilator/verilator -b v5.024 --depth=1 /verilator
WORKDIR /verilator
RUN autoconf
RUN ./configure --prefix=$PWD/install
RUN make -j$(nproc)
RUN make install

ENV PATH="${PATH}:/mill:/verilator/install/bin"
WORKDIR /

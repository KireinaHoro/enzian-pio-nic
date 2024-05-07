FROM ubuntu:22.04

RUN apt-get update

# Mill, the Scala build system
RUN apt-get -y install openjdk-18-jdk-headless curl
RUN mkdir /mill
RUN curl -L https://raw.githubusercontent.com/lefou/millw/0.4.10/millw > /mill/mill
RUN chmod +x /mill/mill

# Verilator
RUN apt-get -y install build-essential autoconf flex bison help2man git libz-dev python3
RUN git clone https://github.com/verilator/verilator -b v5.024 --depth=1 /verilator
WORKDIR /verilator
RUN autoconf
RUN ./configure --prefix=$PWD/install
RUN make -j$(nproc)
RUN make install

# aarch64 cross compiler
RUN apt-get -y install gcc-aarch64-linux-gnu

ENV PATH="${PATH}:/mill:/verilator/install/bin"
WORKDIR /

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

ENV PATH="${PATH}:/mill:/verilator/install/bin"
WORKDIR /

# aarch64 cross compiler
RUN apt-get -y install gcc-aarch64-linux-gnu

# timezone and locale for Vivado
ENV DEBIAN_FRONTEND=noninteractive DEBCONF_NONINTERACTIVE_SEEN=true
RUN <<EOF
cat > /preseed.txt << __eof__
tzdata tzdata/Areas select Europe
tzdata tzdata/Zones/Europe select Zurich

locales locales/locales_to_be_generated multiselect en_US.UTF-8 UTF-8
locales locales/default_environment_locale select en_US.UTF-8
__eof__
debconf-set-selections /preseed.txt

apt-get -y install locales tzdata libtinfo5
EOF
ENV LANG=en_US.UTF-8

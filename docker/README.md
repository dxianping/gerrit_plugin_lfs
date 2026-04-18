# 编译环境搭建
docker compose -f build-env.yml up -d

# Gerrit LFS Plugin编译
git clone https://gerrit.googlesource.com/gerrit
cd gerrit
git checkout -b v3.13.1 v3.13.1
git submodule update --init --recursive

bazel clean --expunge
bazel build plugins/lfs:lfs

# Docker
首次运行
取消注释这句：command: init
执行 docker compose -f run-gerrit.yaml up

再次运行
注释这句：command: init
docker compose -f run-gerrit.yaml up -d

登录，注册账号
https://localhost:6443
username: cn=admin,dc=example,dc=org
password: secret

    Given Name: Gerrit
    Last Name: Admin
    Common Name: Gerrit Admin
    User ID: gerritadmin
    Email: gerritadmin@localdomain
    Password: secret

登录gerrit
https://localhost:8080
Login: gerritadmin
Password: secret
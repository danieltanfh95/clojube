# Clojube

Kubernetes YAML config generator. 

## Rationale

To minimise friction for onboarding any application that runs with docker 19 onto kubernetes.

All apps will use a network port, some storage and an image, however, 
applications usually have a different setup for different environments. 
Clojube will handle generation of YAML of multiple environments for you.

Additionally, clojure also provides the feature where you can replace any 
part of the environment config with the name of another enviroment (as long as it's not in a list) and 
it will automatically be filled in for you. See the `examples` folder.

## Installation

Linux and macOS

### Install bb 
`bash <(curl -s https://raw.githubusercontent.com/borkdude/babashka/master/install)`

### copy the script somewhere
```
install -m +rx -o $USER clojube.clj /usr/local/bin/clojube.clj
```

Windows
### Install bb 

Grab it from [github](https://github.com/borkdude/babashka/releases)
https://github.com/borkdude/babashka/releases/download/v0.2.1/babashka-0.2.1-windows-amd64.zip

### copy the script somewhere 
this should be on your path

## Usage

`clojube.clj config.edn`

Clojube will created YAML files that can be applied by `kubectl apply -f` in the correct order.

You need to provide config.edn, which is some edn file. An edn file is basically similar to JSON, but is far easier to read.

The edn file must include a name, output folder and environments. Example valid EDN:
```edn
{:name          "core"
 :output-folder "output"
 :develop       {:image      "nginx:1.19"
                 :ports      [8001, {:from 8080
                                     :to   30080}]
                 :volumes    [{:path      "/var/www/html"
                               :host-path "/html"
                               :mode      :rw
                               :id        "webroot"}]
                 :deployment {:type   :anywhere
                              :number 1}}
```

### Top level required keys
`:name` : the name of the app

`:output-folder` : this is the output folder relative to the config.edn location

### Environment required keys

`:image` : docker image, must be available with the gitlab-registry-key secret

`:ports` : a list of ports. If it's just a number, it will be a port internal to 
the pod, but if it's a map, then it will create a service with the container port 
and the host port

`:volumes` : a list of volumes, in the docker sense. 
- `:path` : local to the container
- `:host-path` : local to the node (computer)
- `:mode` : `:rw` (read-write) and `:ro` (read-only) is allowed.
- `:id` : optional id

`:deployment` : explains how this should be deployed across the cluster
- `:type` : `:anywhere` and `:per-machine` allowed
- `:number`: replicas created, only when `:anywhere` is selected

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

You need to provide some edn file. An edn file is basically similar to JSON, but is far easier to read.

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

`:name` and `:output-folder` must be present. Anything else will be assumed to be environments.

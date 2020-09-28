# Clojube

Kubernetes YAML config generator. 

## Rationale

To minimise friction for onboarding any application that runs with docker 19 onto kubernetes.

All apps will use a network port, some storage and an image, however, applications usually have a different setup for different environments. Clojube will handle generation of YAML of multiple environments for you.


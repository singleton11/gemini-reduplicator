# How to deploy

```
./amper package
docker buildx build --platform linux/amd64 -t whoishe/huemini .
docker push whoishe/huemini
```

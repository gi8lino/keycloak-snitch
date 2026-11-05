FROM golang:1.27.3-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN go test ./... && CGO_ENABLED=0 go build -trimpath -o /receiver .
FROM scratch
COPY --from=build /receiver /receiver
USER 65532:65532
ENTRYPOINT ["/receiver"]

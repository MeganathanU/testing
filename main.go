package main

import (
	"fmt"
	"net/http"
)

func main() {
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, "Hello from Greens Technologies 🚀 With CI/CD..")
	})
	http.ListenAndServe(":8080", nil)
}
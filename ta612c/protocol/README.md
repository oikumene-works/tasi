# TA612C protocol

This directory contains the current protocol model used by the open tooling in this repository.

The protocol model intentionally distinguishes:

- physically observed behaviour on a TA612C
- vendor documentation
- behaviour found by static analysis of TASI EnvironmentalTester
- third-party implementation evidence
- inference and open questions

Machine-readable summaries are in [`commands.json`](commands.json) and [`ta612c_protocol_facts.json`](ta612c_protocol_facts.json).

The current tools deliberately implement only non-destructive commands that are needed for identification, live data and REC download.

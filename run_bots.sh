#!/bin/bash

export TOURNAMENT_URL="http://localhost:8086"
JAR="rest/target/scala-3.3.4/chess-rest-assembly-1.0.0.jar"
T_ID="fdf600c9"

BOT1_JWT="eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJib3RfZDllMmQxYWEiLCJpc0JvdCI6dHJ1ZSwibmFtZSI6IkJvdDEifQ.lQKIy7EOGZzRvIYzARERSrgFZPUXH1XNPobYh2jzc_g"
BOT2_JWT="eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJib3RfYTM1MWI3ZTMiLCJpc0JvdCI6dHJ1ZSwibmFtZSI6IkJvdDIifQ.54fZPbvUZ84e1g4auIXKot2qusWIWxpTBfzlwOninyg"
BOT3_JWT="eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJib3RfYzM2YzgzZTgiLCJpc0JvdCI6dHJ1ZSwibmFtZSI6IkJvdDMifQ.dkcwif_ctheG1Usy_8ZMGGSte_npzuVIaz0xhLmjwLo"
BOT4_JWT="eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJib3RfZWZlMDUwZDAiLCJpc0JvdCI6dHJ1ZSwibmFtZSI6IkJvdDQifQ.cCOP5p0wYWCVQaBXd6QqDCRq9NXzk7SLp0umkn8ADIg"

echo "Starting Bot1..."
nohup java -cp $JAR chess.rest.TournamentBot $T_ID $BOT1_JWT > bot1.log 2>&1 &
echo "Starting Bot2..."
nohup java -cp $JAR chess.rest.TournamentBot $T_ID $BOT2_JWT > bot2.log 2>&1 &
echo "Starting Bot3..."
nohup java -cp $JAR chess.rest.TournamentBot $T_ID $BOT3_JWT > bot3.log 2>&1 &
echo "Starting Bot4..."
nohup java -cp $JAR chess.rest.TournamentBot $T_ID $BOT4_JWT > bot4.log 2>&1 &

echo "All 4 bots started in the background."

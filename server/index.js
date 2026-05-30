const express = require("express");
const cors    = require("cors");
const morgan  = require("morgan");

const app = express();

// ✅ STATIC FILES (GAME SERVE)
app.use(express.static("server"));

// ✅ BASIC MIDDLEWARES
app.use(cors());
app.use(express.json());
app.use(morgan("dev"));

// ✅ TEST ROUTE
app.get("/", (req, res) => {
  res.send("✅ CallX Server Running");
});

// ✅ OPTIONAL: direct game route (backup)
app.get("/game", (req, res) => {
  res.sendFile(__dirname + "/server/bubble-pop-game.html");
});

// ✅ SERVER START
const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log("🚀 Server running on port " + PORT);
});

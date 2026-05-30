const express = require("express");
const cors    = require("cors");
const morgan  = require("morgan");

const app = express();

// ✅ CORRECT STATIC PATH
app.use(express.static(__dirname));

app.use(cors());
app.use(express.json());
app.use(morgan("dev"));

app.get("/", (req, res) => {
  res.send("Server running ✅");
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log("Server running on port " + PORT);
});

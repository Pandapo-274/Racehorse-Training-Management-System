import { useEffect, useState } from "react";
import "./App.css";

function App() {
  const [message, setMessage] = useState("Loading...");

  useEffect(() => {
    fetch("/api/hello")
      .then((res) => res.text())
      .then((data) => setMessage(data))
      .catch(() => setMessage("Cannot connect to Backend"));
  }, []);

  return (
    <div className="App">
      <h1>Racehorse Training Management System</h1>
      <p>Backend Test: {message}</p>
    </div>
  );
}

export default App;
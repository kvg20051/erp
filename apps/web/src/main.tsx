import React from "react";
import ReactDOM from "react-dom/client";
import { ConfigProvider, App as AntApp } from "antd";
import ru from "antd/locale/ru_RU";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import App from "./App";
import "./style.css";
const client = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
});
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={ru}
      theme={{
        token: {
          colorPrimary: "#236956",
          borderRadius: 10,
          colorBgLayout: "#f3f5f2",
          fontFamily: "Inter, Segoe UI, sans-serif",
        },
      }}
    >
      <AntApp>
        <QueryClientProvider client={client}>
          <App />
        </QueryClientProvider>
      </AntApp>
    </ConfigProvider>
  </React.StrictMode>,
);

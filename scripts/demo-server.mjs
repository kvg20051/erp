import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";
const project=path.resolve(path.dirname(fileURLToPath(import.meta.url)),"..");
const root=path.join(project,"release","web");
if(!fs.existsSync(path.join(root,"index.html")))throw new Error("Compiled web missing. Build apps/web and copy dist to release/web, or use the full ZIP.");
const mime={".html":"text/html; charset=utf-8",".js":"text/javascript; charset=utf-8",".css":"text/css; charset=utf-8",".svg":"image/svg+xml",".png":"image/png",".jpg":"image/jpeg",".json":"application/json"};
http.createServer((req,res)=>{
 if(req.url.startsWith("/api/")||req.url==="/actuator/health"){
  const upstream=http.request({hostname:"127.0.0.1",port:8080,path:req.url,method:req.method,headers:{...req.headers,host:"127.0.0.1:8080"}},r=>{res.writeHead(r.statusCode,r.headers);r.pipe(res);});
  upstream.on("error",()=>{res.writeHead(502,{"Content-Type":"application/json"});res.end(JSON.stringify({detail:"Start Java backend on 127.0.0.1:8080 first."}));});
  req.pipe(upstream);return;
 }
 if(req.method!=="GET"&&req.method!=="HEAD"){res.writeHead(405);res.end();return;}
 let file;try{file=path.resolve(root,"."+decodeURIComponent(new URL(req.url,"http://localhost").pathname));}catch{res.writeHead(400);res.end();return;}
 if(file!==root&&!file.startsWith(root+path.sep)){res.writeHead(403);res.end();return;}
 if(!fs.existsSync(file)||!fs.statSync(file).isFile())file=path.join(root,"index.html");
 res.writeHead(200,{"Content-Type":mime[path.extname(file)]||"application/octet-stream","X-Content-Type-Options":"nosniff","Cache-Control":"no-store"});
 if(req.method==="HEAD")res.end();else fs.createReadStream(file).pipe(res);
}).listen(5173,"127.0.0.1",()=>console.log("wax local preview: http://127.0.0.1:5173 (demo only; Ctrl+C to stop)"));

/** @type {import('next').NextConfig} */
const nextConfig = {
  output: "standalone",
  eslint: {
    ignoreDuringBuilds: true,
  },
  typescript: {
    ignoreBuildErrors: true,
  },
  images: {
    unoptimized: true,
  },

  /**
   * rewrites() tells Next.js to forward certain requests to another server.
   *
   * Without this, when the browser calls `/api/v1/menu`, Next.js looks for
   * a route inside the Next.js app itself and finds nothing.
   *
   * With this, any request starting with /api gets forwarded to our
   * Spring Boot backend running on port 8080. The browser never sees
   * the redirect — it just thinks it's talking to Next.js.
   *
   * :path* is a wildcard that matches everything after /api/
   * So /api/v1/menu → http://localhost:8080/api/v1/menu
   */
  async rewrites() {
    const backendUrl = process.env.BACKEND_URL ?? "http://localhost:8080"
    return [
      {
        source: "/api/:path*",
        destination: `${backendUrl}/api/:path*`,
      },
    ]
  },
}

export default nextConfig

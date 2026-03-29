import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "AtlasWatch — Movie Discovery & Recommendations",
  description: "Discover trending movies, build your watchlist, and share reviews.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body className="antialiased">
        {children}
      </body>
    </html>
  );
}

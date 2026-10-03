using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Identity.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class RetentionIndex : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateIndex(
                name: "IX_refresh_tokens_expire_at_utc",
                schema: "identity",
                table: "refresh_tokens",
                column: "expire_at_utc");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "IX_refresh_tokens_expire_at_utc",
                schema: "identity",
                table: "refresh_tokens");
        }
    }
}
